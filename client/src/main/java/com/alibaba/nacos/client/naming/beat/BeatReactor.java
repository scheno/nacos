/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.client.naming.beat;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.CommonParams;
import com.alibaba.nacos.api.naming.NamingResponseCode;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.client.monitor.MetricsMonitor;
import com.alibaba.nacos.client.naming.net.NamingProxy;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.common.utils.ThreadUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/**
 * Beat reactor.
 *
 * @author harold
 */
public class BeatReactor implements Closeable {

    private final ScheduledExecutorService executorService;

    private final NamingProxy serverProxy;

    private boolean lightBeatEnabled = false;

    public final Map<String, BeatInfo> dom2Beat = new ConcurrentHashMap<String, BeatInfo>();

    public BeatReactor(NamingProxy serverProxy) {
        this(serverProxy, UtilAndComs.DEFAULT_CLIENT_BEAT_THREAD_COUNT);
    }

    public BeatReactor(NamingProxy serverProxy, int threadCount) {
        this.serverProxy = serverProxy;
        this.executorService = new ScheduledThreadPoolExecutor(threadCount, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("com.alibaba.nacos.naming.beat.sender");
                return thread;
            }
        });
    }

    /**
     * Add beat information.
     *
     * @param serviceName service name
     * @param beatInfo    beat information
     */
    public void addBeatInfo(String serviceName, BeatInfo beatInfo) {
        NAMING_LOGGER.info("[BEAT] adding beat: {} to beat map.", beatInfo);
        // DEFAULT_GROUP@@service-provider#192.168.60.1#1010
        String key = buildKey(serviceName, beatInfo.getIp(), beatInfo.getPort());
        BeatInfo existBeat = null;
        //fix #1733 again
        // 心跳信息存储到 Map 中
        if ((existBeat = dom2Beat.put(key, beatInfo)) != null) {
            existBeat.setStopped(true);
        }
        // 创建一个心跳任务，定时发送心跳操作
        executorService.schedule(new BeatTask(beatInfo), beatInfo.getPeriod(), TimeUnit.MILLISECONDS);
        // 注册监控器
        MetricsMonitor.getDom2BeatSizeMonitor().set(dom2Beat.size());
    }

    /**
     * Remove beat information.
     *
     * @param serviceName service name
     * @param ip          ip of beat information
     * @param port        port of beat information
     */
    public void removeBeatInfo(String serviceName, String ip, int port) {
        NAMING_LOGGER.info("[BEAT] removing beat: {}:{}:{} from beat map.", serviceName, ip, port);
        // 将心跳信息从 dom2Beat Map集合中移除
        BeatInfo beatInfo = dom2Beat.remove(buildKey(serviceName, ip, port));
        if (beatInfo == null) {
            return;
        }
        // 设置心跳信息停止，在发起心跳的时候会进行判断beatInfo是否是stop的
        beatInfo.setStopped(true);
        // 指标监听
        MetricsMonitor.getDom2BeatSizeMonitor().set(dom2Beat.size());
    }

    /**
     * Build new beat information.
     *
     * @param instance instance
     * @return new beat information
     */
    public BeatInfo buildBeatInfo(Instance instance) {
        return buildBeatInfo(instance.getServiceName(), instance);
    }

    /**
     * Build new beat information.
     *
     * @param groupedServiceName service name with group name, format: ${groupName}@@${serviceName}
     * @param instance instance
     * @return new beat information
     */
    public BeatInfo buildBeatInfo(String groupedServiceName, Instance instance) {
        // 创建心跳实体
        BeatInfo beatInfo = new BeatInfo();
        // 设置服务名称
        beatInfo.setServiceName(groupedServiceName);
        // 设置IP地址
        beatInfo.setIp(instance.getIp());
        // 设置端口号
        beatInfo.setPort(instance.getPort());
        // 集群的名字是默认的
        beatInfo.setCluster(instance.getClusterName());
        beatInfo.setWeight(instance.getWeight());
        beatInfo.setMetadata(instance.getMetadata());
        beatInfo.setScheduled(false);
        // 5s 发送一次心跳请求
        beatInfo.setPeriod(instance.getInstanceHeartBeatInterval());
        return beatInfo;
    }

    public String buildKey(String serviceName, String ip, int port) {
        return serviceName + Constants.NAMING_INSTANCE_ID_SPLITTER + ip + Constants.NAMING_INSTANCE_ID_SPLITTER + port;
    }

    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        ThreadUtils.shutdownThreadPool(executorService, NAMING_LOGGER);
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }

    class BeatTask implements Runnable {

        BeatInfo beatInfo;

        // 创建心跳
        public BeatTask(BeatInfo beatInfo) {
            this.beatInfo = beatInfo;
        }

        @Override
        public void run() {
            // 如果实例已经停了那么就不用发送心跳了
            if (beatInfo.isStopped()) {
                return;
            }
            long nextTime = beatInfo.getPeriod();
            try {
                // 发送心跳
                JsonNode result = serverProxy.sendBeat(beatInfo, BeatReactor.this.lightBeatEnabled);
                long interval = result.get("clientBeatInterval").asLong();
                boolean lightBeatEnabled = false;
                if (result.has(CommonParams.LIGHT_BEAT_ENABLED)) {
                    lightBeatEnabled = result.get(CommonParams.LIGHT_BEAT_ENABLED).asBoolean();
                }
                BeatReactor.this.lightBeatEnabled = lightBeatEnabled;
                if (interval > 0) {
                    nextTime = interval;
                }
                int code = NamingResponseCode.OK;
                if (result.has(CommonParams.CODE)) {
                    code = result.get(CommonParams.CODE).asInt();
                }
                // 如果心跳实例没有找到的话那么就进行重新注册
                if (code == NamingResponseCode.RESOURCE_NOT_FOUND) {
                    // 构建心跳信息，为了进行实例在服务注册表中找不到的情况下进行重新注册
                    Instance instance = new Instance();
                    instance.setPort(beatInfo.getPort());
                    instance.setIp(beatInfo.getIp());
                    instance.setWeight(beatInfo.getWeight());
                    instance.setMetadata(beatInfo.getMetadata());
                    instance.setClusterName(beatInfo.getCluster());
                    instance.setServiceName(beatInfo.getServiceName());
                    instance.setInstanceId(instance.getInstanceId());
                    instance.setEphemeral(true);
                    try {
                        // 发起服务实例注册
                        serverProxy.registerService(beatInfo.getServiceName(),
                                NamingUtils.getGroupName(beatInfo.getServiceName()), instance);
                    } catch (Exception ignore) {
                    }
                }
            } catch (NacosException ex) {
                NAMING_LOGGER.warn("[CLIENT-BEAT] failed to send beat: {}, code: {}, msg: {}",
                        JacksonUtils.toJson(beatInfo), ex.getErrCode(), ex.getErrMsg());

            } catch (Exception unknownEx) {
                NAMING_LOGGER.error("[CLIENT-BEAT] failed to send beat: {}, unknown exception msg: {}",
                        JacksonUtils.toJson(beatInfo), unknownEx.getMessage(), unknownEx);
            } finally {
                // 再一次进行心跳任务执行
                executorService.schedule(new BeatTask(beatInfo), nextTime, TimeUnit.MILLISECONDS);
            }
        }
    }
}

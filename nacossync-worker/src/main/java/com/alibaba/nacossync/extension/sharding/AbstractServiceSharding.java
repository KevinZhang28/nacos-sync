package com.alibaba.nacossync.extension.sharding;

import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.client.naming.utils.NetUtils;
import com.alibaba.nacossync.constant.ShardingLogTypeEnum;
import com.alibaba.nacossync.extension.impl.extend.Sharding;
import com.alibaba.nacossync.pojo.ShardingLog;
import com.alibaba.nacossync.util.SkyWalkerUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by maj on 2020/10/27.
 */
@Slf4j
public abstract class AbstractServiceSharding implements ServiceSharding, InitializingBean {

    protected volatile List<String> servers = new LinkedList<String>();

    private Map<String, ConcurrentLinkedQueue<ShardingLog>> localServicesChangeMap = new ConcurrentHashMap<String, ConcurrentLinkedQueue<ShardingLog>>();

    private volatile Map<String, TreeSet<String>> localServicesMap = new ConcurrentHashMap<String, TreeSet<String>>();

    private final static String LOCAL_IP = NetUtils.localIP();

    private volatile String serverListMd5;

    private Map<String, Sharding> serverListens = new ConcurrentHashMap<String, Sharding>();

    @Value("${server.port}")
    private String serverPort;

    @Lazy
    @Resource(type = NacosServersManager.class)
    private ServersManager serversManager;

    protected List<String> getServers() {
        return servers;
    }

    protected void listenServer() {
        try {
            serversManager.subscribeServers(new EventListener() {
                @Override
                public void onEvent(Event event) {
                    try {
                        shadingServers();
                        for (Sharding sharding : serverListens.values()) {
                            sharding.onServerChange();
                        }

                    } catch (Exception e) {
                        log.error("subscribe servers faild.", e);
                    }
                }
            });
        } catch (Exception e) {
            log.error("subscribe servers faild.", e);
        }
    }


    protected void shadingServers() throws Exception {
        List<String> serversList = serversManager.getServers();
        Collections.sort(serversList);
        String md5 = SkyWalkerUtil.StringToMd5(serversList.toString());
        if (!md5.equals(serverListMd5)) {
            servers = serversList;
            serverListMd5 = md5;
            doSharding();
        }

    }

    //need fix: 暂时同步话解决
    protected void shadingServices(String key, List<String> serviceNames) {
        if (!localServicesMap.containsKey(key)) {
            TreeSet<String> localServicesSet = new TreeSet<String>();
            localServicesMap.putIfAbsent(key, localServicesSet);
        }
        if (!localServicesChangeMap.containsKey(key)) {
            ConcurrentLinkedQueue<ShardingLog> removeQueue = new ConcurrentLinkedQueue<ShardingLog>();
            localServicesChangeMap.putIfAbsent(key, removeQueue);
        }
        TreeSet<String> localServices = localServicesMap.get(key);
        try {
            for (String serviceName : serviceNames) {
                if (getShardingServer(serviceName).equals(LOCAL_IP + ":" + serverPort)) {
                    if (!localServices.contains(serviceName)) {
                        localServicesMap.get(key).add(serviceName);
                        localServicesChangeMap.get(key).offer(new ShardingLog(serviceName, ShardingLogTypeEnum.ADD.getType()));
                    }
                } else {
                    if (localServices.contains(serviceName)) {
                        localServicesMap.get(key).remove(serviceName);
                        localServicesChangeMap.get(key).offer(new ShardingLog(serviceName, ShardingLogTypeEnum.DELETE.getType()));
                    }
                }

            }
        } catch (Exception e) {
            log.error("shading services faild.", e);
        }
    }

    //need fix：按照service维度做sharding，但是在service维度存在zk->nacos nacos->zk两种service，而目前避免环的处理在instance维度的metadata中，如果每次做sharding都去判断instance消耗太大，而且也不能完全避免service中存在多种源的instance，故目前做法是按照zk和nacos注册上的所有
    //serviceName List做sharding,可能存在sharding不均衡问题，如导致大部分的service都落在一个node上的可能
    @Override
    public void sharding(String key, List<String> serviceNames) {
        try {
            shadingServices(key, serviceNames);
        } catch (Exception e) {
            log.error("sharding faild. sharding key is:{}", key, e);
        }
    }

    @Override
    public boolean addServerChange(String key, Sharding sharding) {
        return serverListens.putIfAbsent(key, sharding) == null ? true : false;
    }

    @Override
    public TreeSet<String> getLocalServices(String key) {
        return localServicesMap.get(key);
    }

    protected abstract void doSharding();

    protected abstract String getShardingServer(String key);

    @Override
    public void afterPropertiesSet() throws Exception {
        listenServer();
    }

    @Override
    public Queue<ShardingLog> getChangeServices(String key) {
        return localServicesChangeMap.get(key);
    }
}

package com.trendmicro.tme.broker;

import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.TextFormat;
import com.sun.messaging.jmq.jmsclient.runtime.BrokerInstance;
import com.sun.messaging.jmq.jmsclient.runtime.ClientRuntime;
import com.trendmicro.codi.CODIException;
import com.trendmicro.codi.DataListener;
import com.trendmicro.codi.DataObserver;
import com.trendmicro.codi.ZKSessionManager;
import com.trendmicro.codi.ZNode;
import com.trendmicro.codi.lock.ZLock;
import com.trendmicro.codi.lock.Lock.LockType;
import com.trendmicro.mist.proto.ZooKeeperInfo;

public class EmbeddedOpenMQ implements DataListener {
    private final static Logger logger = LoggerFactory.getLogger(EmbeddedOpenMQ.class);
    private BrokerInstance brokerInstance;
    private Properties config;
    private String host;
    private boolean statusChanged = false;
    private boolean isOnline = false;
    private DataObserver obs;
    private String mqHome;
    private String mqVar;
    private String mqLib;
    private String tmeRoot;
    
    public EmbeddedOpenMQ(String mqHome, String mqVar, String mqLib, Properties config) throws Exception {
        host = InetAddress.getLocalHost().getHostAddress();
        this.mqHome = mqHome;
        this.mqVar = mqVar;
        this.mqLib = mqLib;
        this.config = config;
        tmeRoot = config.getProperty("com.trendmicro.tme.broker.zookeeper.tmeroot");
        obs = new DataObserver(tmeRoot + "/broker/" + host, this, false, 0);
    }
    
    public void start() throws Exception {
        logger.info("Starting OpenMQ Broker...");
        ClientRuntime clientRuntime = ClientRuntime.getRuntime();
        brokerInstance = clientRuntime.createBrokerInstance();
        
        // Although only -imqhome and -varhome options are listed as possible
        // args, as shown in BrokerInstance.parseArgs(), the -libhome option is
        // also supported according to BrokerProcess.convertArgs(), where the
        // BrokerProcess class is the base class of all three possible
        // JMSBroker implementations.
        //
        // See: com.sun.messaging.jmq.jmsclient.runtime.BrokerInstance
        //      com.sun.messaging.jmq.jmsclient.runtime.impl.BrokerInstanceImpl
        //      com.sun.messaging.jmq.jmsserver.BrokerProcess.convertArgs()
        Properties brokerConfig = brokerInstance.parseArgs(new String[] {
            "-imqhome", mqHome, "-varhome", mqVar, "-libhome", mqLib
        });
        brokerConfig.putAll(config);
        brokerInstance.init(brokerConfig, null);
        brokerInstance.start();
        logger.info("OpenMQ Broker started!");
        isOnline = true;
    }
    
    public void stop() {
        logger.info("Stopping OpenMQ Broker...");
        brokerInstance.stop();
        logger.info("Shutting down OpenMQ Broker...");
        brokerInstance.shutdown();
        logger.info("OpenMQ Broker is down.");
        isOnline = false;
    }
    
    public void registerOnZk() throws CODIException, UnknownHostException {
        ZooKeeperInfo.Broker.Builder brkBuilder = ZooKeeperInfo.Broker.newBuilder();
        brkBuilder.setHost(host);
        brkBuilder.setPort(config.getProperty("imq.portmapper.port"));
        brkBuilder.setStatus(ZooKeeperInfo.Broker.Status.ONLINE);
        brkBuilder.addAccount(ZooKeeperInfo.Broker.Account.newBuilder().setUser("admin").setPassword("admin").build());
        brkBuilder.setBrokerType("openmq");
        brkBuilder.setVersion("4.5.1");
        brkBuilder.setReserved(false);
        
        ZNode brkNode = new ZNode(tmeRoot + "/broker/" + host);
        try {
            brkNode.deleteRecursively();
        }
        catch(Exception e) {
        }
        brkNode.create(false, brkBuilder.build().toString());
        
        ZNode loadingNode = new ZNode(tmeRoot + "/broker/" + host + "/loading");
        loadingNode.create(false, ZooKeeperInfo.Loading.newBuilder().setLoading(0).setLastUpdate(0).setFreeMemory(0).setMaxMemory(0).build().toString());
        obs.start();
    }
    
    public void unregisterOnZk() throws CODIException {
        obs.stop();
        ZNode brkNode = new ZNode(tmeRoot + "/broker/" + host);
        brkNode.deleteRecursively();
    }
    
    private ZooKeeperInfo.Broker.Status getNewStatus() {
        try {
            byte[] data = new ZNode(tmeRoot + "/broker/" + host).getContent();
            ZooKeeperInfo.Broker.Builder builder = ZooKeeperInfo.Broker.newBuilder();
            TextFormat.merge(new String(data), builder);
            return builder.build().getStatus();
        }
        catch(Exception e) {
            logger.error("getNewStatus() error", e);
            return null;
        }
    }
    
    private synchronized boolean checkOnlineStatus() {
        if(statusChanged) {
            String lockPath = tmeRoot + "/locks/brk_" + host;
            ZLock lock = new ZLock(lockPath);
            try {
                logger.info("Waiting for broker lock...");
                lock.acquire(LockType.WRITE_LOCK);
                logger.info("Get broker lock.");
                ZooKeeperInfo.Broker.Status newStatus = getNewStatus();
                if((newStatus == ZooKeeperInfo.Broker.Status.ONLINE) != isOnline) {
                    switch(newStatus) {
                    case OFFLINE:
                        stop();
                        break;
                    case ONLINE:
                        start();
                        break;
                    default:
                        break;
                    }
                }
            }
            catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
            finally {
                try {
                    lock.release();
                    if(lock.getChildren().isEmpty())
                        lock.delete();
                }
                catch(CODIException e) {
                }
            }
            
            statusChanged = false;
        }
        return isOnline;
    }
    
    public void reportLoadingForever() throws InterruptedException {
        ZNode loadingNode = new ZNode(tmeRoot + "/broker/" + host + "/loading");
        while(true) {
            if(checkOnlineStatus()) {
                long free = Runtime.getRuntime().freeMemory();
                long total = Runtime.getRuntime().totalMemory();
                long max = Runtime.getRuntime().maxMemory();
                
                ZooKeeperInfo.Loading.Builder load_builder = ZooKeeperInfo.Loading.newBuilder();
                load_builder.setLoading(Math.round(((float) (max - free) / max) * 100));
                load_builder.setLastUpdate(new Date().getTime());
                load_builder.setFreeMemory(free);
                load_builder.setMaxMemory(max);
                try {
                    loadingNode.setContent(load_builder.build().toString());
                    logger.info(String.format("Reporting loading: free memory: %d, total memory: %d max memory: %d", free, total, max));
                }
                catch(CODIException e) {
                    logger.error("Cannot report loading", e);
                }
            }
            Thread.sleep(5000);
        }
    }
    
    public static void main(String argv[]) throws Exception {
        String mqHome = System.getProperty("com.trendmicro.tme.broker.mqhome", "/opt/trend/tme");
        String mqVar = System.getProperty("com.trendmicro.tme.broker.mqvar", "/var/lib/tme/broker");
        String mqLib = System.getProperty("com.trendmicro.tme.broker.mqlib", "/opt/trend/tme/lib");
        String configPath = System.getProperty("com.trendmicro.tme.broker.config", "/opt/trend/tme/conf/broker/config.properties");
        
        Properties config = new Properties();
        FileInputStream fis = new FileInputStream(configPath);
        config.load(fis);
        IOUtils.closeQuietly(fis);
        
        ZKSessionManager.initialize(config.getProperty("com.trendmicro.tme.broker.zookeeper.quorum"), Integer.valueOf(config.getProperty("com.trendmicro.tme.broker.zookeeper.timeout")));
        
        final EmbeddedOpenMQ mq = new EmbeddedOpenMQ(mqHome, mqVar, mqLib, config);
        mq.start();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                mq.stop();
                try {
                    mq.unregisterOnZk();
                }
                catch(CODIException e) {
                    logger.error("Unable to unregister broker", e);
                }
            }
        });
        
        mq.registerOnZk();
        mq.reportLoadingForever();
    }
    
    @Override
    public synchronized void onDataChanged(String arg0, Map<String, byte[]> changeMap) {
        if(changeMap.get("") == null) // broker node is removed
            return;
        statusChanged = true;
        logger.info("broker status changed");
    }
}

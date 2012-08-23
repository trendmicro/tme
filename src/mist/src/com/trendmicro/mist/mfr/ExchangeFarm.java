package com.trendmicro.mist.mfr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import com.trendmicro.codi.CODIException;
import com.trendmicro.codi.DataListener;
import com.trendmicro.codi.DataObserver;
import com.trendmicro.codi.Node;
import com.trendmicro.codi.NodeListener;
import com.trendmicro.codi.ZNode;
import com.trendmicro.codi.lock.Lock.LockType;
import com.trendmicro.codi.lock.ZLock;
import com.trendmicro.mist.BrokerSpy;
import com.trendmicro.mist.Daemon;
import com.trendmicro.mist.proto.ZooKeeperInfo;
import com.trendmicro.mist.util.Exchange;
import com.trendmicro.mist.util.ParallelExecutor;
import com.trendmicro.spn.common.util.Utils;

public class ExchangeFarm extends Thread implements DataListener {
    private final static Logger logger = LoggerFactory.getLogger(ExchangeFarm.class);
    private static ExchangeFarm m_theSingleton = null;
    private static final String FIXED_NODE = "/global/fixed_exchange";
    private static final ZooKeeperInfo.Reference refdata = ZooKeeperInfo.Reference.newBuilder().setHost(Utils.getHostIP()).build();

    private HashMap<String, String> fixedExchanges = new HashMap<String, String>();

    private HashMap<String, ArrayList<ZNode>> allExchangeRefs = new HashMap<String, ArrayList<ZNode>>();
    private LinkedBlockingDeque<ExchangeEvent> exchangeEventQueue = new LinkedBlockingDeque<ExchangeEvent>();

    private DataObserver fixedObs = null;
    
    public static enum FlowControlBehavior {
        BLOCK, DROP_NEWEST, DROP_OLDEST,
    }

    class ExchangeEvent {
    }

    class RefListener extends ExchangeEvent implements NodeListener {
        private ZNode origNode = null;
        private Exchange exchange = null;

        public RefListener(ZNode origNode, Exchange exchange) {
            this.origNode = origNode;
            this.exchange = exchange;
        }

        @Override
        public boolean onChildrenChanged(Node arg0) {
            return false;
        }

        @Override
        public boolean onConnected() {
            return true;
        }

        @Override
        public boolean onDataChanged(Node arg0) {
            return false;
        }

        @Override
        public boolean onDisconnected() {
            return true;
        }

        @Override
        public boolean onNodeCreated(Node arg0) {
            return false;
        }

        @Override
        public boolean onNodeDeleted(Node arg0) {
            return false;
        }

        @Override
        public boolean onSessionExpired() {
            /**
             * Put a request to the eventQueue, wait for the worker to recreate
             * the expired reference node
             */
            logger.info("ref node " + origNode.getPath() + " expired");
            try {
                exchangeEventQueue.put(this);
            }
            catch(InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
            return false;
        }

        /**
         * The function to recreate the reference node
         */
        public void renewRef() {
            ArrayList<ZNode> refList = allExchangeRefs.get(exchange.toString());
            String lockPath = "/exchange/" + exchange.toString() + ".lock";
            ZLock lock = new ZLock(lockPath);
            try {
                /**
                 * Get the exchange's lock first
                 */
                logger.info("getting exchange lock: " + lockPath);
                lock.acquire(LockType.WRITE_LOCK);
                logger.info("exchange lock: " + lockPath + " acquired");

                /**
                 * If the node does not exist in the refList, it means that it
                 * is discarded by decExchangeRef(), so it can be ignored
                 */
                if(!refList.contains(origNode)) {
                    logger.info("ref Node " + origNode.getPath() + " removed, ignore");
                    return;
                }

                /**
                 * Remove the old node and create a new one
                 */
                refList.remove(origNode);
                String newRefPath = incExchangeRef(exchange);
                logger.info("new ref node created: " + newRefPath);
            }
            catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
            finally {
                try {
                    lock.release();
                }
                catch(Exception e) {
                }
                logger.info("lock released: " + lockPath);
            }
        }
    }

    private ExchangeFarm() {
        fixedObs = new DataObserver(FIXED_NODE, this, true, 0);

        fixedObs.start();
        new Thread(this).start();
    }

    private String queryFixedExchange(String conceptName) {
        String fixed_host = null;
        for(Map.Entry<String, String> e : fixedExchanges.entrySet()) {
            String exchange = e.getKey();
            if(exchange.equals(conceptName)) {
                fixed_host = e.getValue();
                break;
            }
        }
        if(fixed_host == null)
            return null;

        String hostname = null;
        ZooKeeperInfo.Broker broker = Daemon.brokerFarm.getBrokerByHost(fixed_host);
        if(broker != null) {
            if(Daemon.brokerFarm.checkConnectable(broker)) {
                hostname = fixed_host;
                logger.info(String.format("fixed exchange: %s @ %s", conceptName, fixed_host));
            }
            else
                logger.warn(String.format("fixed exchange: %s, but %s not connectable", conceptName, fixed_host));
        }
        else
            logger.warn(String.format("fixed exchange: %s, but %s not found in broker farm", conceptName, fixed_host));

        return hostname;
    }
    
    private boolean isExchangeInUse(String broker, Exchange exchange) {
        BrokerSpy spy = new BrokerSpy(broker);
        try {
            spy.jmxConnectServer();
            Map<String, String> map = spy.getExchangeAttribMap(exchange);
            if(map.isEmpty())
                return false;
            else if(Long.valueOf(map.get("NumMsgs")) > 0)
                return true;
            else if(Integer.valueOf(map.get("NumConsumers")) > 0)
                return true;
            else if(Integer.valueOf(map.get("NumProducers")) > 0)
                return true;
            else
                return false;
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
            return true;
        }
        finally {
            spy.jmxCloseServer();
        }
    }

    /**
     * The Runner of ParallelExecutor to check if the exchange is in use
     */
    class ExchangeJMXChecker implements ParallelExecutor.Runner<String> {
        Exchange exchange;
        String broker;

        public ExchangeJMXChecker(Exchange exchange, String broker) {
            this.exchange = exchange;
            this.broker = broker;
        }

        @Override
        public String run() {
            //if(BrokerAdmin.isExchangeInUse(broker, new Exchange(exchangeName)))
            if(isExchangeInUse(broker, exchange))
                return broker;
            else
                return null;
        }
    }

    /**
     * Wrapper function to use the parallel executor to ask all available
     * brokers if the specified exchange is on that broker. It will return null
     * if the exchange does not exist, or the broker's host which the exchange
     * lives on
     */
    private String checkExistingExchange(Exchange exchange, Set<String> brokerSet) {
        ParallelExecutor<String> pe = new ParallelExecutor<String>();
        for(String broker : brokerSet)
            pe.addRunner(new ExchangeJMXChecker(exchange, broker));

        for(String broker : pe.waitCompleted()) {
            if(broker != null) {
                logger.warn("there is still client using " + exchange + ", reuse broker " + broker);
                return broker;
            }
        }
        return null;
    }

    private String decideExchangeHost(String name, boolean isMigrate) {
        if(Daemon.brokerFarm.getBrokerCount() == 0)
            return null;

        Vector<Vector<String>> loadingScaleVec = new Vector<Vector<String>>();
        for(int i = 0; i < 10; i++) {
            Vector<String> brokers = new Vector<String>();
            loadingScaleVec.add(brokers);
        }

        Map<String, ZooKeeperInfo.Loading> loadingMap = Daemon.brokerFarm.getAllLoading();

        if(!isMigrate) {
            /**
             * If it is exchange migration, then the exchange is definitely
             * exists, so exchange migration does not need the following check
             */
            String existingHost = checkExistingExchange(new Exchange(name), loadingMap.keySet());
            if(existingHost != null)
                return existingHost;
        }

        for(Entry<String, ZooKeeperInfo.Loading> e : loadingMap.entrySet())
            loadingScaleVec.get(e.getValue().getLoading() / 10).add(e.getKey());

        Vector<String> reserved = new Vector<String>();
        for(Vector<String> brokers : loadingScaleVec) {
            if(brokers.isEmpty())
                continue;
            ZooKeeperInfo.Broker b;
            do {
                // select a broker in the same loading scale by exchange name's
                // hash value
                int idx = Math.abs(name.hashCode()) % brokers.size();
                b = Daemon.brokerFarm.getBrokerByHost(brokers.get(idx));
                if(Daemon.brokerFarm.checkConnectable(b)) {
                    if(b.getReserved()) {
                        reserved.add(b.getHost());
                        brokers.remove(idx);
                    }
                    else {
                        logger.info(String.format("decideExchangeHost(%s) return %s", name, b.getHost()));
                        return b.getHost();
                    }
                }
                else {
                    logger.warn(String.format("decideExchangeHost() broker %s is not connectable", b.getHost()));
                    brokers.remove(idx);
                }
            } while(brokers.size() > 0);
        }
        if(reserved.size() > 0) {
            logger.warn(String.format("no broker available, use reserved broker `%s'", reserved.get(0)));
            return reserved.get(0);
        }
        return null;
    }

    private void updateFixedExchanges(Map<String, byte[]> changeMap) {
        for(Entry<String, byte[]> ent : changeMap.entrySet()) {
            if(ent.getKey().length() == 0)
                continue;
            fixedExchanges.remove(ent.getKey());
            if(ent.getValue() != null) {
                ZooKeeperInfo.Exchange.Builder builder = ZooKeeperInfo.Exchange.newBuilder();
                try {
                    TextFormat.merge(new String(ent.getValue()), builder);
                    String host = builder.build().getHost();
                    fixedExchanges.put(ent.getKey(), host);
                }
                catch(ParseException e) {
                    logger.error("cannot update fixed exchange " + ent.getKey() + ": " + new String(ent.getValue()));
                }
            }
        }
    }

    private boolean hasReference(Exchange exchange) {
        try {
            String path = "/exchange/" + exchange.toString();
            ZNode node = new ZNode(path);
            boolean no_ref = ((!node.exists()) || (node.getChildren().size() == 0));
            return !no_ref;
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
            return true;
        }
    }

    private ZNode genRefNode(String refRoot) {
        for(;;) {
            try {
                return ZNode.createSequentialNode(refRoot + "/ref", true, refdata.toString().getBytes());
            }
            catch(CODIException e) {
                logger.error("cannot generate reference node under " + refRoot);
                logger.error(e.getMessage(), e);
                logger.info("retrying...");
                Utils.justSleep(1000);
            }
        }
    }

    // /////////////////////////////////////////////////////////////////////////////////////////

    public static ExchangeFarm getInstance() {
        if(null == m_theSingleton)
            m_theSingleton = new ExchangeFarm();
        return m_theSingleton;
    }

    public void reset() {
        fixedExchanges.clear();
        allExchangeRefs.clear();
        exchangeEventQueue.clear();

        fixedObs = new DataObserver(FIXED_NODE, this, true, 0);

        fixedObs.start();
    }

    public void run() {
        Thread.currentThread().setName("ExchangeEventHandler");
        for(;;) {
            try {
                ExchangeEvent ev = exchangeEventQueue.take();
                if(ev instanceof RefListener)
                    ((RefListener) ev).renewRef();
            }
            catch(InterruptedException e) {
                continue;
            }
        }
    }

    public String incExchangeRef(Exchange exchange) {
        String exchangeFullName = exchange.toString();
        String refRoot = "/exchange/" + exchangeFullName;
        String exchangeRefPath = null;

        ZooKeeperInfo.Exchange.Builder builder = ZooKeeperInfo.Exchange.newBuilder();
        builder.setHost(exchange.getBroker());
        ZooKeeperInfo.Exchange xchgMsg = builder.build();

        /**
         * Try to create and set the reference's root node
         */
        ZNode refRootNode = new ZNode(refRoot);
        for(;;) {
            try {
                if(!refRootNode.exists())
                    refRootNode.create(false, xchgMsg.toString().getBytes());
                else if(new String(refRootNode.getContent()).compareTo(xchgMsg.toString()) != 0)
                    refRootNode.setContent(xchgMsg.toString().getBytes());
                break;
            }
            catch(Exception e) {
                logger.error(e.getMessage(), e);
                Utils.justSleep(1000);
            }
        }

        /**
         * Generate the reference ephemeral node and add it
         */
        ZNode refNode = genRefNode(refRoot);
        refNode.setNodeListener(new RefListener(refNode, exchange));
        exchangeRefPath = refNode.getPath();
        synchronized(allExchangeRefs) { // To prevent concurrent list
                                        // modification
            ArrayList<ZNode> refList = allExchangeRefs.get(exchangeFullName);
            if(refList == null) {
                refList = new ArrayList<ZNode>();
                allExchangeRefs.put(exchangeFullName, refList);
            }
            refList.add(refNode);
        }
        return exchangeRefPath;
    }

    public void decExchangeRef(Exchange exchange) {
        String exchangeFullName = exchange.toString();
        String refRoot = "/exchange/" + exchangeFullName;

        ArrayList<ZNode> refList = allExchangeRefs.get(exchangeFullName);
        ZNode refNode = null;
        refNode = refList.get(0);

        String refPath = refNode.getPath();
        try {
            refNode.delete();
            logger.info("refNode " + refPath + " deleted");
        }
        catch(CODIException.NoNode e) {
            logger.info("refNode " + refPath + " disappeared, ignore it");
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
        }
        refList.remove(0);

        try {
            if(hasReference(exchange))
                return;

            logger.info("decExchangeRef(): no reference, check exchange " + exchangeFullName);
            ZNode exNode = new ZNode(refRoot);
            ZooKeeperInfo.Exchange.Builder builder = ZooKeeperInfo.Exchange.newBuilder();
            TextFormat.merge(new String(exNode.getContent()), builder);
            String broker = builder.build().getHost();
            if(!isExchangeInUse(broker, exchange)) {
                logger.info("decExchangeRef(): no other client and pending message, remove exchange");
                exNode.delete();
            }
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public String queryExchangeHost(Exchange exchange) {
        String realname = exchange.getName();

        String hostname = null;
        if((hostname = queryFixedExchange(realname)) != null)
            return hostname;

        String exchangeFullName = exchange.toString();
        String exchangeNodePath = "/exchange/" + exchangeFullName;

        ZNode exchangeNode = new ZNode(exchangeNodePath);
        try {
            if(!exchangeNode.exists())
                hostname = decideExchangeHost(realname, false);
            else {
                ZooKeeperInfo.Exchange.Builder exBuilder = ZooKeeperInfo.Exchange.newBuilder();
                TextFormat.merge(new String(exchangeNode.getContent()), exBuilder);
                ZooKeeperInfo.Exchange ex = exBuilder.build();
                hostname = ex.getHost();

                ZNode brokerNode = new ZNode("/broker/" + hostname);
                if(hostname.compareTo("") == 0 || !brokerNode.exists()) {
                    hostname = decideExchangeHost(realname, true);
                    exchangeNode.setContent(ZooKeeperInfo.Exchange.newBuilder().setHost(hostname).build().toString().getBytes());
                }
                else {
                    ZooKeeperInfo.Broker.Builder brkBuilder = ZooKeeperInfo.Broker.newBuilder();
                    TextFormat.merge(new String(brokerNode.getContent()), brkBuilder);
                    if(brkBuilder.build().getStatus() != ZooKeeperInfo.Broker.Status.ONLINE) {
                        hostname = decideExchangeHost(realname, false);
                        ex = ZooKeeperInfo.Exchange.newBuilder().mergeFrom(ex).clearHost().setHost(hostname).build();
                        exchangeNode.setContent(ex.toString().getBytes());
                    }
                }
            }
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
        }
        return hostname;
    }

    public static class ExchangeInfo {
        public String name;
        public String host;
        public int refCount;
    }

    public static List<ExchangeInfo> getAllExchanges() {
        List<ExchangeInfo> list = new ArrayList<ExchangeInfo>();
        try {
            ZNode exchangeNode = new ZNode("/exchange");
            List<String> exchanges = exchangeNode.getChildren();
            for(String name : exchanges) {
                try {
                    if(name.endsWith(".lock")) {
                        continue;
                    }

                    ZNode exchangeChildNode = new ZNode("/exchange/" + name);
                    byte[] data = exchangeChildNode.getContent();
                    ZooKeeperInfo.Exchange.Builder ex_builder = ZooKeeperInfo.Exchange.newBuilder();
                    TextFormat.merge(new String(data), ex_builder);
                    ExchangeInfo info = new ExchangeInfo();
                    info.name = name;
                    info.host = ex_builder.build().getHost();
                    info.refCount = exchangeChildNode.getChildren().size();
                    list.add(info);
                }
                catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }
        catch(Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static String getCurrentExchangeHost(Exchange exchange) {
        String host = null;
        String exchangeFullName = exchange.toString();
        String exchangeNodePath = "/exchange/" + exchangeFullName;

        ZNode exchangeNode = new ZNode(exchangeNodePath);
        ZooKeeperInfo.Exchange.Builder exBuilder = ZooKeeperInfo.Exchange.newBuilder();
        try {
            TextFormat.merge(new String(exchangeNode.getContent()), exBuilder);
            ZooKeeperInfo.Exchange ex = exBuilder.build();
            host = ex.getHost();
        }
        catch(Exception e) {
        }
        return host;
    }

    public static FlowControlBehavior getDropPolicy(Exchange exchange) {
        String path = "/global/drop_exchange" + "/" + exchange.getName();
        ZNode dropNode = new ZNode(path);
        try {
            if(dropNode.exists()) {
                ZooKeeperInfo.DropConfig.Builder dropBuilder = ZooKeeperInfo.DropConfig.newBuilder();
                TextFormat.merge(dropNode.getContentString(), dropBuilder);
                ZooKeeperInfo.DropConfig dropConf = dropBuilder.build();
                if(dropConf.getPolicy().equals(ZooKeeperInfo.DropConfig.Policy.NEWEST))
                    return FlowControlBehavior.DROP_NEWEST;
                else
                    return FlowControlBehavior.DROP_OLDEST;
            }
            else
                return FlowControlBehavior.BLOCK;
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
            return FlowControlBehavior.BLOCK;
        }
    }

    public static ZooKeeperInfo.TotalLimit getTotalLimit(Exchange exchange) {
        String path = "/global/limit_exchange" + "/" + exchange.getName();
        ZNode limitNode = new ZNode(path);
        try {
            ZooKeeperInfo.TotalLimit.Builder limitBuilder = ZooKeeperInfo.TotalLimit.newBuilder();
            TextFormat.merge(limitNode.getContentString(), limitBuilder);
            return limitBuilder.build();
        }
        catch(Exception e) {
            return ZooKeeperInfo.TotalLimit.newBuilder().setCount(100000).setSizeBytes(10485760).build();
        }
    }

    @Override
    public void onDataChanged(String parentPath, Map<String, byte[]> changeMap) {
        if(parentPath.compareTo(FIXED_NODE) == 0)
            updateFixedExchanges(changeMap);
    }
}

package com.trendmicro.tme.portal;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;

import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.jmxtrans.model.Query;
import com.googlecode.jmxtrans.model.Result;
import com.googlecode.jmxtrans.model.output.RRDToolWriter;
import com.googlecode.jmxtrans.util.BaseOutputWriter;
import com.googlecode.jmxtrans.util.LifecycleException;
import com.googlecode.jmxtrans.util.ValidationException;
import com.sun.messaging.AdminConnectionConfiguration;
import com.sun.messaging.AdminConnectionFactory;

public class ExchangeMetricWriter extends BaseOutputWriter {
    static class Record {
        private long msgIn = 0;
        private long msgOut = 0;
        private long msgInSize = 0;
        private long msgOutSize = 0;
        private long msgDrop = 0;
        private long timestamp = 0;

        public long getMsgIn() {
            return msgIn;
        }

        public void setMsgIn(long msgIn) {
            this.msgIn = msgIn;
        }

        public long getMsgOut() {
            return msgOut;
        }

        public void setMsgOut(long msgOut) {
            this.msgOut = msgOut;
        }

        public long getMsgDrop() {
            return msgDrop;
        }

        public void setMsgDrop(long msgDrop) {
            this.msgDrop = msgDrop;
        }

        public long getMsgInSize() {
            return msgInSize;
        }

        public void setMsgInSize(long msgInSize) {
            this.msgInSize = msgInSize;
        }

        public long getMsgOutSize() {
            return msgOutSize;
        }

        public void setMsgOutSize(long msgOutSize) {
            this.msgOutSize = msgOutSize;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    static class Config {
        private String limitBehavior;
        private long maxNumMsgs;
        private long maxTotalMsgBytes;

        public String getLimitBehavior() {
            return limitBehavior;
        }

        public void setLimitBehavior(String limitBehavior) {
            this.limitBehavior = limitBehavior;
        }

        public long getMaxNumMsgs() {
            return maxNumMsgs;
        }

        public void setMaxNumMsgs(long maxNumMsgs) {
            this.maxNumMsgs = maxNumMsgs;
        }

        public long getMaxTotalMsgBytes() {
            return maxTotalMsgBytes;
        }

        public void setMaxTotalMsgBytes(long maxTotalMsgBytes) {
            this.maxTotalMsgBytes = maxTotalMsgBytes;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(ExchangeMetricWriter.class);
    private static final String[] MBEAN_INVOKE_SIG = new String[] {
        String.class.getName()
    };
    private static final ObjectName consumerManagerName;
    private static final ObjectName producerManagerName;

    private String templateFile = "";
    private String outputPath = "";
    private Pattern namePattern = Pattern.compile(".*,name=\"([^\"]*)\",.*");
    private Pattern typePattern = Pattern.compile(".*desttype=(.),.*");
    private Pattern configPattern = Pattern.compile(".*,subtype=Config,.*");
    private ObjectMapper mapper = new ObjectMapper();
    private HashMap<String, Record> lastRecords = new HashMap<String, Record>();
    private HashMap<String, Config> lastConfigs = new HashMap<String, Config>();
    private HashMap<String, Long> lastAlertTs = new HashMap<String, Long>();

    private Map<String, RRDToolWriter> writerMap = new HashMap<String, RRDToolWriter>();

    static {
        try {
            consumerManagerName = new ObjectName("com.sun.messaging.jms.server:type=ConsumerManager,subtype=Monitor");
            producerManagerName = new ObjectName("com.sun.messaging.jms.server:type=ProducerManager,subtype=Monitor");
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RRDToolWriter getWriter(String broker, String exchangeName, boolean isQueue) throws LifecycleException {
        String key = broker + exchangeName + (isQueue ? "queue": "topic");
        if(!writerMap.containsKey(key)) {
            RRDToolWriter writer = new RRDToolWriter();
            writer.addSetting(RRDToolWriter.TEMPLATE_FILE, templateFile);
            writer.addSetting(RRDToolWriter.OUTPUT_FILE, String.format("%s/%s-%s.rrd", outputPath, isQueue ? "queue": "topic", exchangeName));
            writer.addSetting(RRDToolWriter.BINARY_PATH, "/usr/bin");
            writer.addSetting(RRDToolWriter.DEBUG, true);
            writer.addSetting(RRDToolWriter.GENERATE, false);
            writerMap.put(key, writer);
        }
        return writerMap.get(key);
    }

    private void queryClients(String broker, String typeName, ExchangeMetric metric) throws Exception {
        JMXConnector connector;
        MBeanServerConnection connection;
        AdminConnectionFactory acf;
        acf = new AdminConnectionFactory();
        acf.setProperty(AdminConnectionConfiguration.imqAddress, broker);
        connector = acf.createConnection();

        try {
            connection = connector.getMBeanServerConnection();
            ObjectName exchangeManagerName = new ObjectName("com.sun.messaging.jms.server:" + typeName);
            String[] consumerIDs = (String[]) connection.invoke(exchangeManagerName, "getConsumerIDs", null, null);
            if(consumerIDs != null) {
                for(String consumerID : consumerIDs) {
                    if(consumerID != null) {
                        try {
                            CompositeData info = (CompositeData) connection.invoke(consumerManagerName, "getConsumerInfoByID", new Object[] {
                                consumerID
                            }, MBEAN_INVOKE_SIG);
                            if(info != null) {
                                metric.addConsumer(info.get("Host").toString());
                            }
                        }
                        catch(Exception e) {
                            logger.warn("cannot get info of consumer ID {}", consumerID);
                        }
                    }
                }
            }

            String[] producerIDs = (String[]) connection.invoke(exchangeManagerName, "getProducerIDs", null, null);
            if(producerIDs != null) {
                for(String producerID : producerIDs) {
                    if(producerID != null) {
                        try {
                            CompositeData info = (CompositeData) connection.invoke(producerManagerName, "getProducerInfoByID", new Object[] {
                                producerID
                            }, MBEAN_INVOKE_SIG);
                            if(info != null) {
                                metric.addProducer(info.get("Host").toString());
                            }
                        }
                        catch(Exception e) {
                            logger.warn("cannot get info of producer ID {}", producerID);
                        }
                    }
                }
            }
        }
        finally {
            connector.close();
        }
    }

    private void storeExchangeConfig(String exchangeName, Query q) {
        if(!lastConfigs.containsKey(exchangeName)) {
            lastConfigs.put(exchangeName, new Config());
        }
        Config c = lastConfigs.get(exchangeName);
        for(Result res : q.getResults()) {
            if(res.getAttributeName().equals("LimitBehavior")) {
                c.setLimitBehavior(res.getValues().get("LimitBehavior").toString());
            }
            else if(res.getAttributeName().equals("MaxTotalMsgBytes")) {
                c.setMaxTotalMsgBytes((Long) res.getValues().get("MaxTotalMsgBytes"));
            }
            else if(res.getAttributeName().equals("MaxNumMsgs")) {
                c.setMaxNumMsgs((Long) res.getValues().get("MaxNumMsgs"));
            }
        }
    }

    private void alert(String exchangeName, long pending, int numConsumers, int numProducers) {
        if(!lastAlertTs.containsKey(exchangeName)) {
            lastAlertTs.put(exchangeName, System.currentTimeMillis());
            return;
        }
        if(System.currentTimeMillis() - lastAlertTs.get(exchangeName) > 60000) {
            lastAlertTs.put(exchangeName, System.currentTimeMillis());
            logger.error(String.format("alert: exchange %s: %d msgs, %d consumers, %d producers", exchangeName, pending, numConsumers, numProducers));
            // TODO: email
        }
    }

    @Override
    public void doWrite(Query q) throws Exception {
        if(q.getResults().isEmpty()) {
            logger.error("Empty query result!");
            return;
        }

        Matcher m = namePattern.matcher(q.getResults().get(0).getTypeName());
        if(!m.matches()) {
            logger.error("Name parsing error: {}", q.getResults().get(0).getTypeName());
            return;
        }
        String exchangeName = m.group(1);

        if(configPattern.matcher(q.getResults().get(0).getTypeName()).matches()) {
            storeExchangeConfig(exchangeName, q);
            return;
        }

        m = typePattern.matcher(q.getResults().get(0).getTypeName());
        if(!m.matches()) {
            logger.error("Type parsing error: {}", q.getResults().get(0).getTypeName());
            return;
        }
        boolean isQueue = m.group(1).equals("q");

        if(exchangeName.equals("mq.sys.dmq")) {
            return;
        }

        RRDToolWriter writer = getWriter(q.getServer().getHost(), exchangeName, isQueue);
        ExchangeMetric metric = new ExchangeMetric(q.getServer().getHost(), isQueue ? "queue": "topic", exchangeName, String.format("%s/%s-%s.rrd", outputPath, isQueue ? "queue": "topic", exchangeName));

        try {
            queryClients(q.getServer().getHost(), q.getResults().get(0).getTypeName(), metric);
        }
        catch(Exception e) {
            logger.error("Cannot obtain consumer and producer information for exchange {} on broker {}", q.getServer().getHost(), exchangeName);
            logger.error(e.getMessage(), e);
        }

        Record lastRecord = lastRecords.get(exchangeName);
        long timestamp = System.currentTimeMillis();
        long numMsgs = 0;
        long numMsgsIn = 0;
        long numMsgsOut = 0;
        long lastConsumed = 0;
        Record currentRecord = new Record();
        currentRecord.setTimestamp(timestamp);
        for(Result res : q.getResults()) {
            if(res.getAttributeName().equals("NumMsgs")) {
                numMsgs = (Long) res.getValues().get("NumMsgs");
                metric.addMetric("Pending", res.getValues().get("NumMsgs").toString());
            }
            else if(res.getAttributeName().equals("NumMsgsIn")) {
                numMsgsIn = (Long) res.getValues().get("NumMsgsIn");
                metric.addMetric("Enqueue", res.getValues().get("NumMsgsIn").toString());
                if(lastRecord == null || lastRecord.getMsgIn() > numMsgsIn) {
                    res.addValue("NumMsgsIn", "0");
                }
                else {
                    res.addValue("NumMsgsIn", String.valueOf((long) ((float) (numMsgsIn - lastRecord.getMsgIn()) / (timestamp - lastRecord.getTimestamp()) * 1000)));
                }
                currentRecord.setMsgIn(numMsgsIn);
            }
            else if(res.getAttributeName().equals("NumMsgsOut")) {
                numMsgsOut = (Long) res.getValues().get("NumMsgsOut");
                metric.addMetric("Dequeue", res.getValues().get("NumMsgsOut").toString());
                if(lastRecord == null || lastRecord.getMsgOut() > numMsgsOut) {
                    res.addValue("NumMsgsOut", "0");
                }
                else {
                    lastConsumed = numMsgsOut - lastRecord.getMsgOut();
                    res.addValue("NumMsgsOut", String.valueOf((long) ((float) lastConsumed / (timestamp - lastRecord.getTimestamp()) * 1000)));
                }
                currentRecord.setMsgOut(numMsgsOut);
            }
            else if(res.getAttributeName().equals("NumMsgsPendingAcks")) {
                metric.addMetric("Pending ACK", res.getValues().get("NumMsgsPendingAcks").toString());
            }
            else if(res.getAttributeName().equals("NumConsumers")) {
                metric.addMetric("Consumers", res.getValues().get("NumConsumers").toString());
            }
            else if(res.getAttributeName().equals("NumProducers")) {
                metric.addMetric("Producers", res.getValues().get("NumProducers").toString());
            }
            else if(res.getAttributeName().equals("MsgBytesIn")) {
                long numMsgsInSize = (Long) res.getValues().get("MsgBytesIn");
                metric.addMetric("Enqueue Size", res.getValues().get("MsgBytesIn").toString());
                if(lastRecord == null || lastRecord.getMsgInSize() > numMsgsInSize) {
                    res.addValue("MsgBytesIn", "0");
                }
                else {
                    res.addValue("MsgBytesIn", String.valueOf((long) ((float) (numMsgsInSize - lastRecord.getMsgInSize()) / (timestamp - lastRecord.getTimestamp()) * 1000)));
                }
                currentRecord.setMsgInSize(numMsgsInSize);
            }
            else if(res.getAttributeName().equals("MsgBytesOut")) {
                long numMsgsOutSize = (Long) res.getValues().get("MsgBytesOut");
                metric.addMetric("Dequeue Size", res.getValues().get("MsgBytesOut").toString());
                if(lastRecord == null || lastRecord.getMsgOutSize() > numMsgsOutSize) {
                    res.addValue("MsgBytesOut", "0");
                }
                else {
                    res.addValue("MsgBytesOut", String.valueOf((long) ((float) (numMsgsOutSize - lastRecord.getMsgOutSize()) / (timestamp - lastRecord.getTimestamp()) * 1000)));
                }
                currentRecord.setMsgOutSize(numMsgsOutSize);
            }
            else if(res.getAttributeName().equals("TotalMsgBytes")) {
                metric.addMetric("Pending Size", res.getValues().get("TotalMsgBytes").toString());
            }
        }
        Config c = lastConfigs.get(exchangeName);
        if(c != null) {
            metric.addMetric("Limit Behavior", c.getLimitBehavior());
            metric.addMetric("Max Pending", Long.toString(c.getMaxNumMsgs()));
            metric.addMetric("Max Pending Size", Long.toString(c.getMaxTotalMsgBytes()));
        }

        if(numMsgs > 0 && lastConsumed == 0) {
            alert(exchangeName, numMsgs, Integer.valueOf(metric.getMetrics().get("Consumers")), Integer.valueOf(metric.getMetrics().get("Producers")));
        }

        long numMsgsDropped = numMsgsIn - numMsgsOut - numMsgs;
        metric.addMetric("Dropped", String.valueOf(numMsgsDropped));
        if(lastRecord == null || lastRecord.getMsgDrop() > numMsgsDropped) {
            q.getResults().get(0).addValue("NumMsgDropped", "0");
        }
        else {
            q.getResults().get(0).addValue("NumMsgDropped", String.valueOf((long) ((float) (numMsgsDropped - lastRecord.getMsgDrop()) / (timestamp - lastRecord.getTimestamp()) * 1000)));
        }
        currentRecord.setMsgDrop(numMsgsDropped);
        lastRecords.put(exchangeName, currentRecord);

        writer.validateSetup(q);
        writer.doWrite(q);

        File file = new File(String.format("%s/%s-%s.json", outputPath, isQueue ? "queue": "topic", exchangeName));
        FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
        try {
            FileLock lock = channel.lock();
            try {
                mapper.writeValue(file, metric);
            }
            catch(Exception e) {
                logger.error("write metric error: ", e);
            }
            lock.release();
        }
        catch(Exception e) {
            logger.error("Cannot lock file {}", file.getAbsolutePath());
        }
        finally {
            channel.close();
        }
    }

    @Override
    public void validateSetup(Query q) throws ValidationException {
        try {
            templateFile = (String) getSettings().get("templateFile");
            outputPath = (String) getSettings().get("outputPath");
        }
        catch(Exception e) {
            throw new ValidationException(e.getMessage(), q);
        }
    }
}

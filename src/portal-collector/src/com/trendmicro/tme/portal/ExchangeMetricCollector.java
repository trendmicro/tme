package com.trendmicro.tme.portal;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.mail.Session;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.jmxtrans.JmxTransformer;
import com.googlecode.jmxtrans.model.JmxProcess;
import com.googlecode.jmxtrans.model.Query;
import com.googlecode.jmxtrans.model.Server;
import com.googlecode.jmxtrans.util.ValidationException;
import com.trendmicro.codi.ZKSessionManager;
import com.trendmicro.codi.ZNode;
import com.trendmicro.tme.mfr.BrokerFarm;
import com.trendmicro.mist.proto.ZooKeeperInfo.Broker;

public class ExchangeMetricCollector {
    private static final String CONFIG_PATH = System.getProperty("com.trendmicro.tme.portal.collector.conf", "/opt/trend/tme/conf/portal-collector/portal-collector.properties");
    private static final Logger logger = LoggerFactory.getLogger(ExchangeMetricCollector.class);

    private BrokerFarm brokerFarm;
    private ExchangeMetricWriter writer;
    private ExchangeMetricArchiver archiver;

    private Query createQuery() {
        Query query = new Query();
        query.setObj("com.sun.messaging.jms.server:type=Destination,subtype=*,desttype=*,name=*");
        query.addAttr("NumMsgs");
        query.addAttr("NumMsgsIn");
        query.addAttr("NumMsgsOut");
        query.addAttr("NumMsgsPendingAcks");
        query.addAttr("NumConsumers");
        query.addAttr("NumProducers");
        query.addAttr("MsgBytesIn");
        query.addAttr("MsgBytesOut");
        query.addAttr("TotalMsgBytes");
        query.addAttr("MaxNumMsgs");
        query.addAttr("MaxTotalMsgBytes");
        query.addAttr("LimitBehavior");
        query.addOutputWriter(writer);
        return query;
    }

    public ExchangeMetricCollector(BrokerFarm brokerFarm, ExchangeMetricWriter writer, ExchangeMetricArchiver archiver) {
        this.brokerFarm = brokerFarm;
        this.writer = writer;
        this.archiver = archiver;
    }

    private String getJMXUrl(String broker) {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(broker, 7676));
            String url = null;
            for(String line : IOUtils.toString(sock.getInputStream()).split("\n")) {
                if(line.startsWith("jmxrmi rmi JMX")) {
                    url = line.substring(line.indexOf('=') + 1, line.length() - 1);
                    break;
                }
            }
            logger.info("jmxurl of broker {} = {}", broker, url);
            return url;
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        finally {
            try {
                IOUtils.closeQuietly(sock.getInputStream());
                IOUtils.closeQuietly(sock.getOutputStream());
                sock.close();
            }
            catch(IOException e) {
            }
        }
    }

    public void run() {
        logger.info("Exchange Metric Collector started");
        while(true) {
            JmxProcess jmxProcess = new JmxProcess();
            for(Broker broker : brokerFarm.getAllBrokers().values()) {
                if(broker.getStatus() == Broker.Status.ONLINE) {
                    Server server = new Server();
                    server.setHost(broker.getHost());
                    server.setUsername("admin");
                    server.setPassword("admin");
                    String jmxurl = getJMXUrl(broker.getHost());
                    if(jmxurl == null) {
                        logger.error("cannot obtain JMX URL from broker {}", broker.getHost());
                        continue;
                    }
                    server.setUrl(jmxurl);

                    try {
                        server.addQuery(createQuery());
                    }
                    catch(ValidationException e) {
                        e.printStackTrace();
                    }
                    jmxProcess.addServer(server);
                }
            }
            JmxTransformer transformer = new JmxTransformer();
            try {
                transformer.setRunEndlessly(false);
                transformer.executeStandalone(jmxProcess);
                Thread.sleep(10000);
            }
            catch(Exception e) {
                logger.error("Error to collect metrics: ", e);
            }
            archiver.execute();
        }
    }

    public static void main(String[] args) {
        try {
            Properties prop = new Properties();
            prop.load(new FileInputStream(CONFIG_PATH));
            // Let the system properties override the ones in the config file
            prop.putAll(System.getProperties());

            String zkQuorum = prop.getProperty("com.trendmicro.tme.portal.collector.zookeeper.quorum");
            int zkTimeout = Integer.valueOf(prop.getProperty("com.trendmicro.tme.portal.collector.zookeeper.timeout"));

            ZKSessionManager.initialize(zkQuorum + prop.getProperty("com.trendmicro.tme.portal.collector.zookeeper.tmeroot"), zkTimeout);
            if(!ZKSessionManager.instance().waitConnected(zkTimeout)) {
                throw new Exception("Cannot connect to ZooKeeper Quorom " + zkQuorum);
            }

            Session mailSession = null;
            ZNode mailReceiverNode = new ZNode("/global/mail_alert");
            List<String> receivers = new ArrayList<String>();
            if(mailReceiverNode.exists()) {
                String receiversStr = mailReceiverNode.getContentString().trim();
                if(!receiversStr.isEmpty()) {
                    receivers = Arrays.asList(receiversStr.split(";"));
                    logger.info("sending e-mail alert to {}", receivers);
                }
                if(!receivers.isEmpty()) {
                    Properties mailProps = new Properties();
                    mailProps.put("mail.smtp.host", new ZNode("/global/mail_smtp").getContentString());
                    mailProps.put("mail.from", new ZNode("/global/mail_sender").getContentString());
                    mailSession = Session.getInstance(mailProps, null);
                }
            }
            ExchangeMetricWriter writer = new ExchangeMetricWriter(receivers, Integer.valueOf(prop.getProperty("com.trendmicro.tme.portal.collector.alert.interval", "60")), mailSession);
            writer.addSetting("templateFile", prop.getProperty("com.trendmicro.tme.portal.collector.template"));
            writer.addSetting("outputPath", prop.getProperty("com.trendmicro.tme.portal.collector.outputdir"));
            ExchangeMetricArchiver archiver = new ExchangeMetricArchiver((String) prop.getProperty("com.trendmicro.tme.portal.collector.outputdir"), (String) prop.getProperty("com.trendmicro.tme.portal.collector.archiver.maxrecords"), Integer.valueOf((String) prop.getProperty("com.trendmicro.tme.portal.collector.archiver.interval")));
            ExchangeMetricCollector collector = new ExchangeMetricCollector(new BrokerFarm(), writer, archiver);
            collector.run();
        }
        catch(Exception e) {
            e.printStackTrace();
            logger.error("Portal Collector startup error: ", e);
            System.exit(-1);
        }
    }
}

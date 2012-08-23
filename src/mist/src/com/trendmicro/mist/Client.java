package com.trendmicro.mist;

import java.util.HashMap;
import java.util.Map;

import javax.jms.BytesMessage;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.trendmicro.codi.CODIException;
import com.trendmicro.codi.lock.Lock.LockType;
import com.trendmicro.codi.lock.ZLock;
import com.trendmicro.mist.mfr.ExchangeFarm;
import com.trendmicro.mist.proto.GateTalk;
import com.trendmicro.mist.proto.ZooKeeperInfo;
import com.trendmicro.mist.util.Exchange;

public class Client {
    private GateTalk.Client clientConfig;
    private GateTalk.Session sessionConfig;
    private int myId = -1;
    private static int clientIdCnt = 0;
    private Connection connection;
    private final static Logger logger = LoggerFactory.getLogger(Client.class);

    private boolean opened = false;
    private javax.jms.Session jms_session;
    private MessageConsumer consumer;
    private MessageProducer producer;
    private Destination destination;
    private Exchange exchange = new Exchange();
    private boolean brokerDetermined = false;
    private Object closeLock = new Object();

    ////////////////////////////////////////////////////////////////////////////////

    public Client(GateTalk.Client client_config, GateTalk.Session sess_config) {
        clientConfig = client_config;
        sessionConfig = sess_config;
        myId = clientIdCnt++;
        exchange.set(getChannelName());
        if(isQueue())
            exchange.setQueue();
        else
            exchange.setTopic();
        ExchangeMetric.initExchangeMetric(exchange);
    }

    public Connection getConnection() {
        return connection;
    }

    public GateTalk.Client getConfig() {
        return clientConfig;
    }

    public javax.jms.MessageProducer getProducer() {
        return producer;
    }

    public javax.jms.MessageConsumer getConsumer() {
        return consumer;
    }

    public javax.jms.Session getJMSSession() {
        return jms_session;
    }

    public String getChannelName() {
        return clientConfig.getChannel().getName();
    }
    
    public Exchange getExchange() {
        return exchange;
    }
    
    public String getBrokerHost() {
        return exchange.getBroker();
    }

    public int getId() {
        return myId;
    }

    public String getInfo() {
        return String.format("%s@%s:%s", isConsumer() ? "consumer": "producer", isQueue() ? "queue": "topic", getChannelName());
    }

    public int getSessionId() {
        return clientConfig.getSessionId();
    }

    public boolean isConsumer() {
        return (clientConfig.getType() == GateTalk.Client.Type.CONSUMER);
    }

    public boolean isQueue() {
        return (clientConfig.getChannel().getType() == GateTalk.Channel.Type.QUEUE);
    }
    
    public synchronized void openClient(boolean determined, boolean isResume, boolean isMigrate) throws MistException {
        Connection oldConnection = null;
        if(isResume)
            oldConnection = connection;
        if(opened) {
            logger.info("client already opened, skip");
            return;
        }
        
        String lockPath = "/exchange/" + exchange.toString() + ".lock";
        ZLock lock = new ZLock(lockPath);
        brokerDetermined = determined;
        try {
            if(brokerDetermined) {
                exchange.setBroker(sessionConfig.getConnection().getHostName());
                logger.info(String.format("exchange %s uses determined broker %s", exchange.getName(), exchange.getBroker()));
                connection = Daemon.getConnection(sessionConfig.getConnection());
            }
            else {
                logger.info("getting exchange lock: " + lockPath);
                lock.acquire(LockType.WRITE_LOCK);
                logger.info("exchange lock: " + lockPath + " acquired");

                exchange.setBroker(ExchangeFarm.getInstance().queryExchangeHost(exchange));
                if(exchange.getBroker() == null)
                    throw new MistException(String.format("can not request broker from MFR for `%s:%s'", isQueue() ? "queue": "topic", getChannelName()));
                logger.info(String.format("request exchange %s, select broker: %s", exchange.getName(), exchange.getBroker()));
                connection = Daemon.getConnection(exchange.getBroker());
            }
            
            if(isResume)
                oldConnection.decreaseReference();
            
            javax.jms.Connection jms_conn = connection.getJMSConnection();
            jms_session = jms_conn.createSession(false, javax.jms.Session.CLIENT_ACKNOWLEDGE);
            
            if(isQueue())
                destination = jms_session.createQueue(exchange.getName());
            else
                destination = jms_session.createTopic(exchange.getName());

            if(isConsumer()) {
                consumer = jms_session.createConsumer(destination);
            }
            else {
                producer = jms_session.createProducer(destination);
                if(getConfig().getChannel().getPersistent())
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                else
                    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
            }
            logger.info(String.format("attached to %s", getInfo()));

            if(!brokerDetermined) {
                if(!isResume && !isMigrate) {
                    String exchangeRefPath = ExchangeFarm.getInstance().incExchangeRef(exchange);
                    logger.info("exchangeRefPath added: " + exchangeRefPath);
                }
            }
            try {
                BrokerSpy.setExchangeFlowControl(exchange, ExchangeFarm.getDropPolicy(exchange));
            }
            catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
            ZooKeeperInfo.TotalLimit limit = ExchangeFarm.getTotalLimit(exchange);
            try {
                BrokerSpy.setExchangeTotalLimit(exchange, limit.getSizeBytes(), limit.getCount());
            }
            catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
            opened = true;
        }
        catch(JMSException e) {
            logger.error("fail to create session level objects: " + e.getMessage(), e);
            throw new MistException(e.getMessage());
        }
        catch(CODIException e) {
            logger.error(e.getMessage(), e);
            throw new MistException(e.getMessage());
        }
        catch(InterruptedException e) {
            logger.error(e.getMessage(), e);
            throw new MistException(e.getMessage());
        }
        finally {
            if(!brokerDetermined) {
                try {
                    lock.release();
                }
                catch(CODIException e) {
                }
                logger.info("lock released: " + lockPath);
            }
        }
    }

    public void closeClient(boolean isPause, boolean isMigrate) {
        synchronized(closeLock) {
            if(!opened) {
                if(isMigrate)
                    connection.decreaseReference();
                logger.info("client not yet opened, skip");
                return;
            }
            opened = false;
        }
        
        String lockPath = "/exchange/" + exchange.toString() + ".lock";
        ZLock lock = new ZLock(lockPath);
        try {
            logger.info("closing jms client");
            if(isConsumer())
                consumer.close();
            else
                producer.close();

            logger.info("closing client: jms_session");
            jms_session.close();
            logger.info(String.format("detached from %s", getInfo()));
            if(!isPause)
                connection.decreaseReference();

            if(!brokerDetermined) {
                logger.info("getting lock: " + lockPath);
                lock.acquire(LockType.WRITE_LOCK);
                logger.info("lock acquired: " + lockPath);
                if(!isPause && !isMigrate)
                    ExchangeFarm.getInstance().decExchangeRef(exchange);
            }
        }
        catch(JMSException e) {
            logger.error("fail to close session level objects: " + e.getMessage(), e);
        }
        catch(CODIException e) {
            logger.error(e.getMessage(), e);
        }
        catch(InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        finally {
            if(!brokerDetermined) {
                try {
                    lock.release();
                }
                catch(CODIException e) {
                }
                logger.info("lock released: " + lockPath);
            }
        }
    }

    public synchronized void sendMessageBytes(byte[] data, HashMap<String, String> props) throws MistException {
        try {
            BytesMessage message = getJMSSession().createBytesMessage();
            message.setBooleanProperty("JMS_SUN_COMPRESS", true);
            for(Map.Entry<String, String> ent: props.entrySet()) {
                try {
                    message.setStringProperty(ent.getKey(), ent.getValue());
                }
                catch(JMSException e) {
                    logger.warn(String.format("property (%s, %s) can not be set, skip", ent.getKey(), ent.getValue()));
                }
            }
            message.writeBytes(data);
            getProducer().send(message);
        }
        catch(JMSException e) {
            logger.error(e.getMessage(), e);
            throw new MistException(String.format("producer (%d): %s", getSessionId(), e.getMessage()));
        }
    }

    public synchronized void sendMessageBytes(byte[] data) throws MistException {
        try {
            BytesMessage message = getJMSSession().createBytesMessage();
            message.writeBytes(data);
            getProducer().send(message);
        }
        catch(JMSException e) {
            logger.error(e.getMessage(), e);
            throw new MistException(String.format("producer (%d): %s", getSessionId(), e.getMessage()));
        }
    }
}

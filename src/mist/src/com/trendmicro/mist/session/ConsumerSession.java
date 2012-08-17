package com.trendmicro.mist.session;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Iterator;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.Topic;

import com.google.protobuf.ByteString;
import com.trendmicro.mist.Client;
import com.trendmicro.mist.Daemon;
import com.trendmicro.mist.ExchangeMetric;
import com.trendmicro.mist.MistException;
import com.trendmicro.mist.proto.GateTalk;
import com.trendmicro.mist.proto.MistMessage;
import com.trendmicro.mist.proto.MistMessage.KeyValuePair;
import com.trendmicro.mist.proto.MistMessage.MessageBlock;
import com.trendmicro.mist.util.Exchange;
import com.trendmicro.mist.util.MessageFilter;
import com.trendmicro.mist.util.Packet;
import com.trendmicro.spn.common.util.Utils;

public class ConsumerSession extends Session implements MessageListener {
    public static class MessagePrepared {
        public MistMessage.MessageBlock msgBlock;
        public Exchange from;
        public MessageBlock.Builder builder;
        
        private byte[] convertJMSMessage(BytesMessage msg) throws JMSException, IOException {
            ByteString.Output byteOut = ByteString.newOutput();
            byte[] buf = new byte[1024];
            int len = -1;
            while((len = msg.readBytes(buf)) > 0)
                byteOut.write(buf, 0, len);
            return byteOut.toByteString().toByteArray();
        }
        
        public MessagePrepared(BytesMessage msg) throws MistException, JMSException, IOException {
            Destination d = msg.getJMSDestination();
            if(d instanceof Queue)
                from = new Exchange("queue:" + ((Queue) d).getQueueName());
            else if(d instanceof Topic)
                from = new Exchange("topic:" + ((Topic) d).getTopicName());
            
            builder = MistMessage.MessageBlock.newBuilder();
            builder.setId(from.toString());
            
            byte[] payload = convertJMSMessage(msg);
            builder.setMessage(ByteString.copyFrom(payload));

            Enumeration<?> propNames = msg.getPropertyNames();
            while(propNames.hasMoreElements()) {
                String key = (String) propNames.nextElement();
                String value = msg.getStringProperty(key);
                if(key.equals("MIST_TTL"))
                    builder.setTtl(Long.valueOf(value));
                else
                    builder.addProperties(KeyValuePair.newBuilder().setKey(key).setValue(value).build());
            }

            Iterator<MessageFilter> iter = Daemon.messageFilters.descendingIterator();
            while(iter.hasNext()){
                iter.next().postReceive(this);
            }
            msgBlock = builder.build();
        }
    }
    
    private Packet pack = new Packet();
    private String prefixThreadName = "";
    private boolean attached = false;
    private boolean unack = false;
    
    public ConsumerSession(int sessId, GateTalk.Session sessConfig) throws MistException {
        super(sessId, sessConfig);
        prefixThreadName = "Session-" + sessId + "-";
    }
    
    @Override
    public void run() {
        if(!acceptConnection())
            return;
        open(false);
        isReady = true;
        attached = true;
        unack = false;
        
        for(Client c : allClients.values()) {
            try {
                c.getConsumer().setMessageListener(this);
            }
            catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }
    
    @Override
    public void addClientIfAttached(Client c) {
        try {
            c.getConsumer().setMessageListener(this);
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
        }
    }
    
    @Override
    protected void detach() {
        attached = false;
        detachNow = true;
        for(int i = 0; i < 10; i++) {
            if(!unack)
                break;
            Utils.justSleep(500);
        }
        unack = false;
        try {
            socketInput.close();
        }
        catch(Exception e) {
        }
    }
    
    private void onMessageProcess(Message msg) throws IOException {
        if(detachNow)
            return;
        MessagePrepared mp = null;
        try {
            mp = new MessagePrepared((BytesMessage) msg);
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
            try {
                msg.acknowledge();
            }
            catch(JMSException e1) {
            }
            return;
        }
        
        pack.setPayload(mp.msgBlock.toByteArray());
        unack = true;
        pack.write(socketOutput);
        
        ExchangeMetric metric = ExchangeMetric.getExchangeMetric(mp.from);
        metric.increaseMessageIn(mp.msgBlock.getMessage().size());
        
        if(pack.read(socketInput) <= 0)
            return;
        
        try {
            if(GateTalk.Response.newBuilder().mergeFrom(pack.getPayload()).build().getSuccess())
                msg.acknowledge();
            unack = false;
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
        }
    }
    
    @Override
    public synchronized void onMessage(Message msg) {
        if(detachNow){
            return;
        }
        String imqThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName(prefixThreadName + imqThreadName);
        try {
            onMessageProcess(msg);
        }
        catch(Exception e) {
            logger.error("Encounter error during delivering message, detaching the session", e);
            try {
                detach(GateTalk.Request.Role.SOURCE);
            }
            catch(MistException e1) {
                logger.error(e1.getMessage(), e1);
            }
        }
        Thread.currentThread().setName(imqThreadName);
    }
    
    @Override
    public boolean isAttached() {
        return attached;
    }
}

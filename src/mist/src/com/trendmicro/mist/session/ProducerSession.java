package com.trendmicro.mist.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.jms.Message;

import com.trendmicro.mist.Client;
import com.trendmicro.mist.Daemon;
import com.trendmicro.mist.ExchangeMetric;
import com.trendmicro.mist.MistException;
import com.trendmicro.mist.mfr.RouteFarm;
import com.trendmicro.mist.proto.GateTalk;
import com.trendmicro.mist.proto.MistMessage;
import com.trendmicro.mist.proto.MistMessage.KeyValuePair;
import com.trendmicro.mist.util.Exchange;
import com.trendmicro.mist.util.MessageFilter;
import com.trendmicro.mist.util.Packet;
import com.trendmicro.spn.common.util.Utils;

public class ProducerSession extends Session {
    /**
     * After the producer receives a message from socket, it transforms the raw
     * content by create a MessagePrepared object
     */
    public static class MessagePrepared {
        /**
         * The message's destination exchange.
         */
        public Exchange dest;

        /**
         * The TTL of the message. It will remain the default TTL of JMS if not
         * specified in message
         */
        public long ttl = Message.DEFAULT_TIME_TO_LIVE;

        /**
         * The message body
         */
        public byte[] msg;

        /**
         * The JMS property map, if not in the mist message then it will be null
         * Note that if ttl in MessageBlock is set, then a property named
         * "MIST_TTL" will be added to carry over the TTL value
         */
        public HashMap<String, String> props = null;

        /**
         * Where a raw message gets prepared. A MistException will be thrown if
         * the incoming message cannot be delivered
         * 
         * @param raw
         *            The incoming raw message from socket
         * @throws MistException
         *             It contains the reason why the message cannot be
         *             delivered
         */
        public MessagePrepared(byte[] raw) throws MistException {
            // Try to parse the raw message as MistMessage.MessageBlock
            MistMessage.MessageBlock mBlock = null;
            try {
                MistMessage.MessageBlock.Builder mblockBuilder = MistMessage.MessageBlock.newBuilder();
                mblockBuilder.mergeFrom(raw);
                mBlock = mblockBuilder.build();
            }
            catch(Exception e) {
                throw new MistException(MistException.UNABLE_TO_PARSE_MIST_MESSAGE);
            }

            // Unpack the attributes
            dest = new Exchange(mBlock.getId());
            if(mBlock.hasTtl()) {
                props = new HashMap<String, String>();
                ttl = mBlock.getTtl();
                props.put(Session.MIST_MESSAGE_TTL, new Long(ttl).toString());
            }

            msg = mBlock.getMessage().toByteArray();
            if(mBlock.getPropertiesCount() > 0) {
                if(props == null)
                    props = new HashMap<String, String>();
                for(KeyValuePair pair : mBlock.getPropertiesList())
                    props.put(pair.getKey(), pair.getValue());
            }

            for(MessageFilter filter : Daemon.messageFilters) {
                filter.preSend(this);
            }

            if(msg.length > Daemon.MAX_TRANSMIT_MESSAGE_SIZE)
                throw new MistException(MistException.sizeTooLarge(msg.length));
        }
    }

    private static final long ROUTE_CACHE_TTL_MILLIS = 2000;
    private long lastRouteUpdate;
    private boolean retryDeliver = true;

    private HashMap<Exchange, List<Exchange>> routeCacheMap = new HashMap<Exchange, List<Exchange>>();

    /**
     * Update the routing information from RouteFarm
     */
    private void updateRoute(Exchange ex) {
        // If the current cache has not expired, skip the update
        if((System.currentTimeMillis() - lastRouteUpdate) < ROUTE_CACHE_TTL_MILLIS)
            return;

        // Get a copied list from the RouteFarm
        List<Exchange> destList = RouteFarm.getInstance().getDestList(ex.getName());
        
        if(destList != null) {
            routeCacheMap.put(ex, destList);
        }
        else
            routeCacheMap.remove(ex);

        lastRouteUpdate = System.currentTimeMillis();
    }

    public ProducerSession(int sessId, GateTalk.Session sessConfig) throws MistException {
        super(sessId, sessConfig);
        // TODO Auto-generated constructor stub
    }

    /**
     * The helper function to ack the client with status and fail reason
     * 
     * @param packet
     *            The Packet object used to communicate with client
     * @param success
     *            If the attempt to deliver the message is success or not
     * @param reason
     *            If success, this parameter is ignored; if fail, this parameter
     *            is set into the Exception field of the response message
     */
    private void ackClient(Packet packet, boolean success, String reason) {
        final byte[] successAck = GateTalk.Response.newBuilder().setSuccess(true).build().toByteArray();
        if(success)
            packet.setPayload(successAck);
        else
            packet.setPayload(GateTalk.Response.newBuilder().setSuccess(false).setException(reason).build().toByteArray());
        try {
            packet.write(socketOutput);
        }
        catch(Exception e) {
            logger.error(e.getMessage());
        }
    }

    private Client mountAndAddProducer(Exchange exchange) throws MistException {
        GateTalk.Channel.Builder ch_builder = GateTalk.Channel.newBuilder();
        if(exchange.isQueue()) {
            ch_builder.setType(GateTalk.Channel.Type.QUEUE);
            ch_builder.setName(exchange.getName());
        }
        else {
            ch_builder.setType(GateTalk.Channel.Type.TOPIC);
            ch_builder.setName(exchange.getName());
        }

        GateTalk.Client.Builder cl_builder = GateTalk.Client.newBuilder();
        cl_builder.setSessionId(sessionId);
        cl_builder.setChannel(ch_builder.build());
        cl_builder.setType(GateTalk.Client.Type.PRODUCER);
        cl_builder.setAction(GateTalk.Client.Action.MOUNT);
        GateTalk.Client client_config = cl_builder.build();

        if(!Exchange.isValidExchange(client_config.getChannel().getName()))
            throw new MistException(String.format("exchange `%s' not valid", client_config.getChannel().getName()));

        Client client = null;
        try {
            client = addClient(client_config);
            logger.info(String.format("session %d: create exchange `%s:%s'", sessionId, client.isQueue() ? "queue": "topic", client.getChannelName()));
        }
        catch(MistException e) {
            logger.error(e.getMessage());
            throw e;
        }
        return client;
    }

    protected void deliverMessage(byte[] msg, long ttl, HashMap<String, String> props, List<Exchange> destList) {
        for(Exchange dest : destList) {
            if(dest.getName().length() == 0)
                continue;

            while(retryDeliver) {
                try {
                    Client c = null;
                    synchronized(allClients) {
                        c = findClient(dest);
                        if(c == null || c.getProducer() == null)
                            c = mountAndAddProducer(dest);
                    }
                    c.getProducer().setTimeToLive(ttl);
                    if(props != null)
                        c.sendMessageBytes(msg, props);
                    else
                        c.sendMessageBytes(msg);

                    ExchangeMetric metric = ExchangeMetric.getExchangeMetric(dest);
                    metric.increaseMessageOut(msg.length);
                    break;
                }
                catch(Exception e) {
                    logger.warn("problem in deliverMessage(): ", e);
                    Utils.justSleep(1000);
                }
            }
        }
    }

    private void sendLoop() {
        lastRouteUpdate = -1;
        routeCacheMap.clear();
        // Accept the incoming connection and setup socket IO streams
        if(!acceptConnection())
            return;
        open(false);
        retryDeliver = true;
        isReady = true;

        Packet packet = new Packet();
        List<Exchange> notRoutedDest = new ArrayList<Exchange>();

        // The main loop to handle incoming messages to be sent
        while(!detachNow) {
            try {
                int rdcnt;
                // Reads a message from socket
                // If negative, indicates the other side shutdowns
                if((rdcnt = packet.read(socketInput)) < 0)
                    return;
                // If zero, which means invalid message size, ackClient and
                // continue
                else if(rdcnt == 0) {
                    ackClient(packet, false, MistException.INVALID_MESSAGE_SIZE);
                    continue;
                }
            }
            catch(IOException e) {
                // Socket exception happens, if it is not caused by detach, log
                // the error
                if(!detachNow)
                    logger.error(e.getMessage(), e);
                return;
            }

            // Received a message from socket, try to deliver it

            // Prepare the message to be send
            MessagePrepared mp = null;
            try {
                mp = new MessagePrepared(packet.getPayload());
            }
            catch(MistException e) {
                // If any error occurs, acknowledge fail response and reason
                // to client and continue to handle next message
                ackClient(packet, false, e.getMessage());
                logger.error(e.getMessage());
                continue;
            }

            // Updates the routing cache before delivering the message
            updateRoute(mp.dest);

            // Get the routing destination list
            List<Exchange> destList = routeCacheMap.get(mp.dest);
            if(destList == null) {
                // If the message is not routed, use the not routed
                // destination
                notRoutedDest.clear();
                notRoutedDest.add(mp.dest);
                destList = notRoutedDest;
            }

            // deliver the message
            try {
                deliverMessage(mp.msg, mp.ttl, mp.props, destList);
            }
            catch(Exception e) {
                ackClient(packet, false, e.getMessage());
                logger.error(e.getMessage());
                continue;
            }

            ackClient(packet, true, null);
        }
    }

    @Override
    public void run() {
        sendLoop();
        close(false);
    }

    @Override
    public void addClientIfAttached(Client c) {
        // TODO Auto-generated method stub

    }

    @Override
    protected void detach() {
        // Do not get more messages from socket
        try {
            socketInput.close();
        }
        catch(Exception e) {
        }

        // Wait for 10 seconds to deliver the final message
        try {
            sessionThread.join(10000);
        }
        catch(InterruptedException e) {
        }
        if(sessionThread.isAlive()) {
            retryDeliver = false;
            logger.error("force closing the producer, the message might not have been delivered!");
        }
    }

    @Override
    public boolean isAttached() {
        if(sessionThread == null)
            return false;
        else
            return sessionThread.isAlive();
    }
}

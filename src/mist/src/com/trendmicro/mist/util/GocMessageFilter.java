package com.trendmicro.mist.util;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.trendmicro.codi.ZNode;
import com.trendmicro.mist.Daemon;
import com.trendmicro.mist.MistException;
import com.trendmicro.mist.proto.MistMessage.KeyValuePair;
import com.trendmicro.mist.session.ConsumerSession;
import com.trendmicro.mist.session.ProducerSession;

public class GocMessageFilter implements MessageFilter {
    private final static String GOC_REF = "GOC_REF";
    private final static Logger logger = LoggerFactory.getLogger(GocMessageFilter.class);
    private long m_last_update = 0;
    private URL gocUrl = null;
    
    private URL getGOCUrl() {
        final int INTERVAL = 60 * 1000; // 1 mins
        long now = System.currentTimeMillis();
        
        if(now - m_last_update > INTERVAL) {
            m_last_update = now;
            
            try {
                gocUrl = new URL(new ZNode("/global/goc_server").getContentString());
            }
            catch(Exception e) {
                logger.error(e.getMessage(), e);
                gocUrl = null;
            }
        }
        return gocUrl;
    }
    
    @Override
    public void preSend(ProducerSession.MessagePrepared msg) throws MistException {
        if(msg.msg.length < Daemon.MAX_TRANSMIT_MESSAGE_SIZE) {
            return;
        }
        
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) getGOCUrl().openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            long ttl = msg.ttl;
            if(ttl == 0){
                // default to 1 hr if not set
                ttl = 3600000;
            }
            conn.setRequestProperty("x-trend-goc-expire", String.valueOf((System.currentTimeMillis() + ttl) / 1000));
            conn.connect();
            conn.getOutputStream().write(msg.msg);
            
            if(conn.getResponseCode() == HttpURLConnection.HTTP_CREATED) {
                String ref = conn.getHeaderField("location");
                if(msg.props == null) {
                    msg.props = new HashMap<String, String>();
                }
                msg.props.put(GOC_REF, ref);
                msg.msg = "".getBytes();
            }
            else {
                throw new MistException("Unable to upload");
            }
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
            throw new MistException(e.getMessage());
        }
        finally {
            try {
                conn.getOutputStream().close();
            }
            catch(Exception e) {
            }
            try {
                conn.getInputStream().close();
            }
            catch(Exception e) {
            }
            try {
                conn.disconnect();
            }
            catch(Exception e) {
            }
        }
        
    }
    
    @Override
    public void postReceive(ConsumerSession.MessagePrepared msg) throws MistException {
        Iterator<KeyValuePair> iter = msg.builder.getPropertiesList().iterator();
        while(iter.hasNext()) {
            KeyValuePair kv = iter.next();
            if(kv.getKey().equals(GOC_REF)) {
                String ref = kv.getValue();
                
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(ref).openConnection();
                    conn.setDoInput(true);
                    conn.setDoOutput(false);
                    conn.setRequestMethod("GET");
                    conn.connect();
                    
                    if(conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        msg.builder.setMessage(ByteString.copyFrom(IOUtils.toByteArray(conn.getInputStream())));
                    }
                    else {
                        throw new MistException("unable to download " + ref);
                    }
                }
                catch(Exception e) {
                    logger.error(e.getMessage(), e);
                    throw new MistException(e.getMessage());
                }
                finally {
                    try {
                        conn.getOutputStream().close();
                    }
                    catch(Exception e) {
                    }
                    try {
                        conn.getInputStream().close();
                    }
                    catch(Exception e) {
                    }
                    conn.disconnect();
                }
                break;
            }
        }
    }
    
}

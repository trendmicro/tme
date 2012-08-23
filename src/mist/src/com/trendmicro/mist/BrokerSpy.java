
package com.trendmicro.mist;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.remote.JMXConnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.messaging.AdminConnectionConfiguration;
import com.sun.messaging.AdminConnectionFactory;
import com.trendmicro.mist.mfr.ExchangeFarm;
import com.trendmicro.mist.mfr.ExchangeFarm.FlowControlBehavior;
import com.trendmicro.mist.proto.ZooKeeperInfo;
import com.trendmicro.mist.util.Exchange;
import com.trendmicro.spn.common.util.Utils;

public class BrokerSpy {
    private final static Logger logger = LoggerFactory.getLogger(BrokerSpy.class);
    private String brokerHost;
    private JMXConnector connector;
    private MBeanServerConnection connection;

    ////////////////////////////////////////////////////////////////////////////////
    
	public BrokerSpy(String host) {
	    brokerHost = host;
	}

    public String getExchangeAttribs(boolean isQueue, String exchangeName, String attrib) {
        String pattern = String.format("com.sun.messaging.jms.server:type=Destination,subtype=Monitor,desttype=%s,name=\"%s\"", isQueue ? "q": "t", exchangeName);
        try {
            jmxConnectServer();
            Map<String, String> map = getMBeanAttributesMap(pattern, null);
            return map.get(attrib);
        }
        catch(Exception e) {
            logger.error(e.getMessage());
        }
        finally {
            jmxCloseServer();
        }
        return null;
    }
    
    public Map<String,String> getExchangeAttribMap(Exchange exchange) {
        String pattern = String.format("com.sun.messaging.jms.server:type=Destination,subtype=Monitor,desttype=%s,name=\"%s\"", exchange.isQueue() ? "q": "t", exchange.getName());
        return getMBeanAttributesMap(pattern, null);
    }
    
    public void setExchangeAttrib(Exchange exchange, String attribName, Object attribValue) {
        try {
            String pattern = String.format("com.sun.messaging.jms.server:type=Destination,subtype=Config,desttype=%s,name=\"%s\"", exchange.isQueue() ? "q": "t", exchange.getName());
            ObjectName name = connection.queryNames(new ObjectName(pattern), null).iterator().next();
            connection.setAttribute(name, new Attribute(attribName, attribValue));
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
        }
    }
	
	public ZooKeeperInfo.Loading doSpy() throws MistException {
		try {
            jmxConnectServer();

            ObjectName objname = new ObjectName("com.sun.messaging.jms.server:type=JVM,subtype=Monitor");
            List<Attribute> attrList = connection.getAttributes(objname, new String[] {
                "FreeMemory", "MaxMemory"
            }).asList();
            long free = (Long) (attrList.get(0).getValue());
            long max = (Long) (attrList.get(1).getValue());
            ZooKeeperInfo.Loading.Builder load_builder = ZooKeeperInfo.Loading.newBuilder();
            load_builder.setLoading(Math.round(((float) (max - free) / max) * 100));
            load_builder.setLastUpdate(new Date().getTime());
            load_builder.setFreeMemory(free);
            load_builder.setMaxMemory(max);
            return load_builder.build();
		} 
		catch(Exception e) {
		    throw new MistException(e.getMessage());
		}
		finally {
            jmxCloseServer();
		}
	}
	
	public void jmxConnectServer() throws Exception {
        Exception e = null;
        for(int i = 0; i <= 60; i++) {
            try {
                AdminConnectionFactory acf;
                acf = new AdminConnectionFactory();
                acf.setProperty(AdminConnectionConfiguration.imqAddress, brokerHost);
                connector = acf.createConnection();
                connection = connector.getMBeanServerConnection();
                return;
            }
            catch(Exception e2) {
                logger.warn("jmx connect error: retry #" + i);
                e = e2;
                Utils.justSleep(1000);
            }
        }
        logger.error("jmxConnectServer gave up retrying");
        throw e;
    }

    public void jmxCloseServer() {
        try {
            if(connector != null)
                connector.close();
        }
        catch(IOException e) {
            logger.error(e.getMessage());
        }
    }

	public String[] getMBeanAttributeNames(ObjectName name) {
        try {
            MBeanInfo info = connection.getMBeanInfo(name);
            MBeanAttributeInfo[] attrinfo = info.getAttributes();
            String[] attrnames = new String[attrinfo.length];
            int i = 0;
            for(MBeanAttributeInfo a : attrinfo)
                attrnames[i++] = a.getName();
            return attrnames;
        }
        catch(Exception e) {
            System.err.println(e.getMessage());
        }
        return null;
    }

    public Map<String, AttributeList> getMBeanAttributes(String name, QueryExp query) {
        try {
            Set<ObjectName> names = connection.queryNames(new ObjectName(name), query);
            HashMap<String, AttributeList> map = new HashMap<String, AttributeList>();
            for(ObjectName n : names) {
                String objname = n.getCanonicalName();
                String[] attrnames = getMBeanAttributeNames(n);
                AttributeList attrs = connection.getAttributes(n, attrnames);
                map.put(objname, attrs);
            }
            return map;
        }
        catch(Exception e) {
            System.err.println(e.getMessage());
        }
        return null;
    }

    public Map<String, String> getMBeanAttributesMap(String name, QueryExp query) {
        Map<String, AttributeList> map = getMBeanAttributes(name, query);
        Map<String, String> result = new HashMap<String, String>();
        Set<String> keys = map.keySet();
        for(String k : keys) {
            List<Attribute> attrs = map.get(k).asList();
            for(Attribute a : attrs)
                result.put(a.getName(), (a.getValue() == null) ? "": a.getValue().toString());
        }
        return result;
    }

    public String getBrokerHost() {
        return brokerHost;
    }

    public ArrayList<Exchange> getAllExchangeMetadata() throws Exception{        
        try {
            jmxConnectServer();
            ArrayList<Exchange> result = new ArrayList<Exchange>();

            ObjectName objname = new ObjectName("com.sun.messaging.jms.server:type=Destination,subtype=Monitor,desttype=q,name=*");
            for(ObjectName n : connection.queryNames(objname, null)) {
                Exchange excQ = new Exchange(connection.getAttribute(n, "Name").toString());
                excQ.setQueue();
                result.add(excQ);
            }
            objname = new ObjectName("com.sun.messaging.jms.server:type=Destination,subtype=Monitor,desttype=t,name=*");
            for(ObjectName n : connection.queryNames(objname, null)) {
                Exchange excT = new Exchange(connection.getAttribute(n, "Name").toString());
                excT.setTopic();
                result.add(excT);
            }
            Exchange excAdmin = new Exchange("mq.sys.dmq");
            excAdmin.setQueue();
            result.remove(excAdmin);
            return result;
        } 
        catch(Exception e) {
            throw new MistException(e.getMessage());
        }
        finally {
            jmxCloseServer();
        }        
    }
    
    public Object invokeMBeanMethod(String name, String op_name, Object[] params) throws MistException {
        Object result = null;
        try {
            jmxConnectServer();
            Set<ObjectName> names = connection.queryNames(new ObjectName(name), null);
            for(ObjectName n : names) {
                result = connection.invoke(n, op_name, params, null);
                break;
            }            
        }catch(Exception e) {
            throw new MistException(e.getMessage());
        }
        finally {
            jmxCloseServer();
        }     

        return result;
    }
    
    public static void setExchangeFlowControl(Exchange exchange, FlowControlBehavior policy) throws Exception {
        BrokerSpy spy = null;
        try {
            String broker = ExchangeFarm.getCurrentExchangeHost(exchange);
            if(broker == null)
                return;
            spy = new BrokerSpy(broker);
            spy.jmxConnectServer();

            switch(policy) {
            case BLOCK:
                spy.setExchangeAttrib(exchange, "LimitBehavior", "FLOW_CONTROL");
                break;
            case DROP_NEWEST:
                spy.setExchangeAttrib(exchange, "LimitBehavior", "REJECT_NEWEST");
                break;
            case DROP_OLDEST:
                spy.setExchangeAttrib(exchange, "LimitBehavior", "REMOVE_OLDEST");
                break;
            }
        }
        finally {
            try {
                spy.jmxCloseServer();
            }
            catch(Exception e) {
            }
        }
    }
    
    public static void setExchangeTotalLimit(Exchange exchange, long sizeBytes, long count) throws Exception {
        BrokerSpy spy = null;
        try {
            String broker = ExchangeFarm.getCurrentExchangeHost(exchange);
            if(broker == null)
                return;
            spy = new BrokerSpy(broker);
            spy.jmxConnectServer();

            spy.setExchangeAttrib(exchange, "MaxTotalMsgBytes", sizeBytes);
            spy.setExchangeAttrib(exchange, "MaxNumMsgs", count);
        }
        finally {
            try {
                spy.jmxCloseServer();
            }
            catch(Exception e) {
            }
        }
    }
}

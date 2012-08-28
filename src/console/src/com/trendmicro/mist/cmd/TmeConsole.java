package com.trendmicro.mist.cmd;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.Principal;
import java.sql.DriverManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.jms.BytesMessage;
import javax.jms.ConnectionFactory;
import javax.jms.TextMessage;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginContext;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.nocrala.tools.texttablefmt.CellStyle;
import org.nocrala.tools.texttablefmt.CellStyle.HorizontalAlign;
import org.nocrala.tools.texttablefmt.Table;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import com.sun.security.auth.UserPrincipal;
import com.trendmicro.codi.CODIException;
import com.trendmicro.codi.Id;
import com.trendmicro.codi.Perms;
import com.trendmicro.codi.ZKSessionManager;
import com.trendmicro.codi.ZNode;
import com.trendmicro.codi.lock.Lock.LockType;
import com.trendmicro.codi.lock.ZLock;
import com.trendmicro.mist.BrokerSpy;
import com.trendmicro.mist.Daemon;
import com.trendmicro.mist.client.MistClient;
import com.trendmicro.mist.client.MistClient.Role;
import com.trendmicro.mist.console.CommandExecutable;
import com.trendmicro.mist.console.Console;
import com.trendmicro.mist.mfr.ExchangeFarm;
import com.trendmicro.mist.proto.MistMessage;
import com.trendmicro.mist.proto.MistMessage.KeyValuePair;
import com.trendmicro.mist.proto.ZooKeeperInfo;
import com.trendmicro.mist.proto.ZooKeeperInfo.DropConfig;
import com.trendmicro.mist.util.Exchange;
import com.trendmicro.spn.common.util.Utils;
import com.trendmicro.tme.mfr.BrokerFarm;

public class TmeConsole {
    private static BrokerFarm brokerFarm = null;
    private static TmeConsole myApp;
    private static Console myConsole = new Console("tme-console");
    private static ZLock consoleLock = null;
    private CellStyle numberStyle = new CellStyle(HorizontalAlign.right);
    private static String loggedUser = null;

    private class MigrateThread extends Thread {
        class Forwarder extends Thread {
            private Exchange fwdExchange;
            private boolean ready = false;
            private boolean done = false;
            private boolean go = false;

            public Forwarder(Exchange exchange) {
                fwdExchange = exchange;
            }

            public boolean getReady() {
                return ready;
            }

            public void done() {
                done = true;
            }

            public void go() {
                go = true;
            }

            public void run() {
                MistClient sinkClient = null;
                try {
                    sinkClient = new MistClient(Role.PRODUCER, 1);
                    sinkClient.mount(true, fwdExchange.toString());
                    sinkClient.attach();
                }
                catch(Exception e) {
                    e.printStackTrace();
                    return;
                }

                if(fwdExchange.getBroker() == null) {
                    ready = true;
                    try {
                        sinkClient.close();
                    }
                    catch(Exception e) {
                        e.printStackTrace();
                    }
                    return;
                }

                javax.jms.Connection fromConn = null;
                javax.jms.Session fromSess = null;
                javax.jms.MessageConsumer fromConsumer = null;
                try {
                    ConnectionFactory fromFact = new com.sun.messaging.ConnectionFactory();
                    ((com.sun.messaging.ConnectionFactory) fromFact).setProperty(com.sun.messaging.ConnectionConfiguration.imqBrokerHostName, fwdExchange.getBroker());
                    ((com.sun.messaging.ConnectionFactory) fromFact).setProperty(com.sun.messaging.ConnectionConfiguration.imqBrokerHostPort, "7676");
                    ((com.sun.messaging.ConnectionFactory) fromFact).setProperty(com.sun.messaging.ConnectionConfiguration.imqDefaultUsername, "admin");
                    ((com.sun.messaging.ConnectionFactory) fromFact).setProperty(com.sun.messaging.ConnectionConfiguration.imqDefaultPassword, "admin");
                    fromConn = fromFact.createConnection();
                    fromConn.start();

                    fromSess = fromConn.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE);
                    javax.jms.Destination fromDest;
                    if(fwdExchange.isQueue())
                        fromDest = fromSess.createQueue(fwdExchange.getName());
                    else
                        fromDest = fromSess.createTopic(fwdExchange.getName());
                    fromConsumer = fromSess.createConsumer(fromDest);
                }
                catch(Exception e) {
                    e.printStackTrace();
                }
                ready = true;

                for(;;) {
                    if(!go) {
                        Utils.justSleep(100);
                    }
                    else
                        break;
                }

                while(!done) {
                    try {
                        javax.jms.Message msg = fromConsumer.receive(50);
                        if(msg != null) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            try {
                                if(msg instanceof BytesMessage) {
                                    byte[] buffer = new byte[256];
                                    int ret = -1;
                                    while((ret = ((BytesMessage) msg).readBytes(buffer)) > 0)
                                        bos.write(buffer, 0, ret);
                                }
                                else if(msg instanceof TextMessage) {
                                    byte[] buffer = ((TextMessage) msg).getText().getBytes("UTF-8");
                                    bos.write(buffer, 0, buffer.length);
                                }
                            }
                            catch(Exception e) {
                                e.printStackTrace();
                                continue;
                            }

                            byte[] buffer = bos.toByteArray();
                            MistMessage.MessageBlock.Builder msgBuilder = MistMessage.MessageBlock.newBuilder().setId(fwdExchange.toString()).setMessage(ByteString.copyFrom(buffer));
                            Enumeration<?> propNames = msg.getPropertyNames();
                            while(propNames.hasMoreElements()) {
                                String key = (String) propNames.nextElement();
                                String value = msg.getStringProperty(key);
                                if(key.equals("MIST_TTL"))
                                    msgBuilder.setTtl(Long.valueOf(value));
                                else
                                    msgBuilder.addProperties(KeyValuePair.newBuilder().setKey(key).setValue(value).build());
                            }
                            sinkClient.writeMessage(msgBuilder.build());
                            totalForwardedCount++;
                        }
                    }
                    catch(Exception e) {
                        e.printStackTrace();
                    }
                }

                try {
                    fromConsumer.close();
                    fromSess.close();
                    fromConn.close();

                    sinkClient.close();
                }
                catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private Exchange miExchange;
        private int pendingCnt = -1;
        private boolean isReady = false;
        private long totalForwardedCount = 0;

        public Exchange getExchange() {
            return miExchange;
        }

        public int getPendingCnt() {
            return pendingCnt;
        }

        public boolean isReady() {
            return isReady;
        }

        public MigrateThread(Exchange exchange) {
            miExchange = exchange;
        }
        
        public long getMigrateCount(){
            return totalForwardedCount;
        }

        public void migrate() {
            String nodePath = miExchange.toString();

            try {
                ZNode node = new ZNode("/exchange/" + nodePath);
                ZooKeeperInfo.Exchange.Builder exBuilder = ZooKeeperInfo.Exchange.newBuilder();
                try {
                    TextFormat.merge(new String(node.getContent()), exBuilder);
                }
                catch(CODIException.NoNode e) {
                    myConsole.logResponseNL("exchange " + miExchange.toString() + " does not exist!");
                    isReady = true;
                    return;
                }
                String oldhost = exBuilder.build().getHost();

                ZooKeeperInfo.Broker brk = brokerFarm.getBrokerByHost(oldhost);
                boolean hasOld = !(brk == null);
                if(hasOld) {
                    hasOld = false;
                    Socket sock = null;
                    try {
                        sock = new Socket();
                        sock.setReuseAddress(true);
                        sock.setTcpNoDelay(true);
                        sock.connect(new InetSocketAddress(brk.getHost(), Integer.parseInt(brk.getPort())));
                        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                        int wait_cnt;
                        for(wait_cnt = 0; wait_cnt < 20 && !in.ready(); wait_cnt++) {
                            Utils.justSleep(500);
                        }
                        if(in.ready()) {
                            if(brk.getBrokerType().equals("openmq")) {
                                String line = in.readLine();
                                if(line.startsWith("101 imqbroker"))
                                    hasOld = true;
                            }
                        }
                    }
                    catch(IOException e) {
                    }
                    finally {
                        try {
                            sock.getInputStream().close();
                            sock.close();
                        }
                        catch(IOException e) {
                        }
                    }
                }

                node.setContent(ZooKeeperInfo.Exchange.newBuilder().setHost("").build().toString().getBytes());

                miExchange.setBroker(hasOld ? oldhost: null);
                Forwarder fwder = new Forwarder(miExchange);
                fwder.start();
                while(!fwder.getReady()) {
                    Utils.justSleep(100);
                }
                isReady = true;

                String newhost = "";
                for(;;) {
                    exBuilder = ZooKeeperInfo.Exchange.newBuilder();
                    TextFormat.merge(new String(node.getContent()), exBuilder);
                    newhost = exBuilder.build().getHost();

                    if(newhost.compareTo(oldhost) == 0) {
                        myConsole.logResponse("[%s] warning: exchange is still on the same broker %s, migration completed%n", miExchange.toString(), newhost);
                        fwder.done();
                        fwder.go();
                        fwder.join();
                        return;
                    }
                    else if(newhost.compareTo("") != 0)
                        break;

                    myConsole.logResponse("[%s] waiting for clients to migrate...%n", miExchange.toString());
                    Utils.justSleep(1000);
                }
                myConsole.logResponse("[%s] exchange has been created on %s%n", miExchange.toString(), newhost);

                fwder.go();

                List<String> refList = node.getChildren();
                ArrayList<String> hostList = new ArrayList<String>();
                for(String refPath : refList) {
                    String data = new String(new ZNode("/exchange/" + nodePath + "/" + refPath).getContent());
                    ZooKeeperInfo.Reference.Builder refBuilder = ZooKeeperInfo.Reference.newBuilder();
                    TextFormat.merge(data, refBuilder);
                    ZooKeeperInfo.Reference ref = refBuilder.build();
                    String host = ref.getHost();
                    if(!hostList.contains(host))
                        hostList.add(host);
                }

                myConsole.logResponse("[%s] %d mist clients to migrate: %n", miExchange.toString(), hostList.size());
                for(String host : hostList) {
                    myConsole.logResponse("[%s] informing %n", miExchange.toString(), host);
                    ZooKeeperInfo.Command cmd = ZooKeeperInfo.Command.newBuilder().setType(ZooKeeperInfo.Command.Type.MIGRATE_EXCHANGE).addArgument(nodePath).build();
                    ZNode.createSequentialNode("/local/mist_client/" + host + "/cmd", false, cmd.toString().getBytes());
                }

                if(hasOld) {
                    BrokerSpy brokerSpy = new BrokerSpy(oldhost);

                    for(;;) {
                        String producer = brokerSpy.getExchangeAttribs(miExchange.isQueue(), miExchange.getName(), "NumProducers");
                        String consumer = brokerSpy.getExchangeAttribs(miExchange.isQueue(), miExchange.getName(), "NumConsumers");
                        if(producer.compareTo("0") == 0 && consumer.compareTo("1") == 0)
                            break;
                        else {
                            myConsole.logResponse("[%s] waiting for %s producer(s) and %d consumer(s) to migrate%n", miExchange.toString(), producer, (Integer.valueOf(consumer) - 1));
                            Utils.justSleep(1000);
                        }
                    }

                    for(;;) {
                        String pending = brokerSpy.getExchangeAttribs(miExchange.isQueue(), miExchange.getName(), "NumMsgs");
                        if(pending.compareTo("0") == 0)
                            break;
                        try {
                            pendingCnt = Integer.valueOf(pending);
                        }
                        catch(Exception e) {
                        }
                        Utils.justSleep(1000);
                    }
                }

                fwder.done();
                fwder.join();
            }
            catch(Exception e) {
                e.printStackTrace();
            }
        }

        public void run() {
            migrate();
        }
    }

    private class BrokerCommand implements CommandExecutable {
        private Options opts = new Options();

        private void list() {
            Map<String, ZooKeeperInfo.Loading> loading = brokerFarm.getAllLoading();
            Vector<String> reserved = new Vector<String>();
            Table tab = new Table(5);
            tab.addCell("Broker");
            tab.addCell("Status");
            tab.addCell("Loading");
            tab.addCell("Reserved");
            tab.addCell("Last Update");
            for(ZooKeeperInfo.Broker b: brokerFarm.getAllBrokers().values()){
                tab.addCell(b.getHost() + ":" + b.getPort());
                tab.addCell(b.getStatus().toString());
                tab.addCell(String.valueOf(loading.get(b.getHost()).getLoading()), numberStyle);
                tab.addCell(String.valueOf(b.getReserved()), new CellStyle(HorizontalAlign.center));
                tab.addCell(new SimpleDateFormat("HH:mm:ss").format(loading.get(b.getHost()).getLastUpdate()), new CellStyle(HorizontalAlign.center));
                if(b.getReserved())
                    reserved.add(b.getHost());
            }
            myConsole.logResponseNL(tab.render());

            HashMap<String, List<String>> allFixed = new HashMap<String, List<String>>();
            try {
                ZNode fixNode = new ZNode(ExchangeCommand.ZNODE_FIXED);
                List<String> nodes = fixNode.getChildren();
                for(String name: nodes) {
                    ZooKeeperInfo.Exchange.Builder builder = ZooKeeperInfo.Exchange.newBuilder();
                    TextFormat.merge(new String(new ZNode(ExchangeCommand.ZNODE_FIXED + "/" + name).getContent()), builder);
                    String host = builder.build().getHost();
                    if(allFixed.containsKey(host))
                        allFixed.get(host).add(name);
                    else {
                        List<String> v = new ArrayList<String>();
                        v.add(name);
                        allFixed.put(host, v);
                    }
                }
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }

            for(String host: reserved) {
                if(allFixed.containsKey(host)) {
                    myConsole.logResponse("Broker %s reserved for: %n", host);
                    for(String ex_name: allFixed.get(host))
                        myConsole.logResponse("    %s%n", ex_name);
                }
            }
        }

        private void start(String broker_ip) {
            if(!isValidBrokerIP(broker_ip)) {
                myConsole.logResponse("broker_ip `%s' not available%n", broker_ip);
                return;
            }
            if(brokerFarm.getBrokerByHost(broker_ip).getStatus() == ZooKeeperInfo.Broker.Status.ONLINE) {
                myConsole.logResponse("broker `%s' is already started%n", broker_ip);
                return;
            }

            myConsole.logResponse("starting `%s' ... ", broker_ip);
            String lockPath = "/locks/brk_" + broker_ip;
            ZLock brokerLock = new ZLock(lockPath);
            ZNode brokerNode = new ZNode("/broker/" + broker_ip);
            try{

                brokerLock.acquire(LockType.WRITE_LOCK);
                ZooKeeperInfo.Broker.Builder brkBuilder = ZooKeeperInfo.Broker.newBuilder();
                TextFormat.merge(new String(brokerNode.getContent()), brkBuilder);
                brkBuilder.clearStatus().setStatus(ZooKeeperInfo.Broker.Status.ONLINE);
                brokerNode.setContent(brkBuilder.build().toString().getBytes());
                myConsole.logResponseNL("success");
            }
            catch(Exception e){
                myConsole.logResponseNL("failed");
                myConsole.logResponseNL(e.toString());
            }
            finally {
                try {
                    brokerLock.release();
                    if(brokerLock.getChildren().isEmpty())
                        brokerLock.delete();
                }
                catch(CODIException e) {
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void stop(String broker_ip) {
            if(!isValidBrokerIP(broker_ip)) {
                myConsole.logResponse("broker_ip `%s' not available%n", broker_ip);
                return;
            }
            if(brokerFarm.getBrokerByHost(broker_ip).getStatus() == ZooKeeperInfo.Broker.Status.OFFLINE) {
                myConsole.logResponse("broker `%s' is already stopped%n", broker_ip);
                return;
            }

            int availBrkCnt = 0;
            for(ZooKeeperInfo.Broker b : brokerFarm.getAllBrokers().values()){
                if(b.getStatus() == ZooKeeperInfo.Broker.Status.ONLINE)
                    availBrkCnt++;
            }
            if(availBrkCnt <= 1) {
                System.out.print("You're stopping the last broker in cluster!!! Are you sure? (Y/N): ");
                try {
                    int c = System.in.read();
                    if(c != 'Y' && c != 'y') {
                        myConsole.logResponseNL("Aborted");
                        return;
                    }
                }
                catch(Exception e) {
                }
            }

            myConsole.logResponse("stopping `%s' ...%n", broker_ip);
            String lockPath = "/locks/brk_" + broker_ip;
            ZLock brokerLock = new ZLock(lockPath);
            ZNode brokerNode = new ZNode("/broker/" + broker_ip);
            try {
                brokerLock.acquire(LockType.WRITE_LOCK);
                ZooKeeperInfo.Broker.Builder brkBuilder = ZooKeeperInfo.Broker.newBuilder();
                TextFormat.merge(new String(brokerNode.getContent()), brkBuilder);
                brkBuilder.clearStatus().setStatus(ZooKeeperInfo.Broker.Status.OFFLINE);
                brokerNode.setContent(brkBuilder.build().toString().getBytes());

                if(availBrkCnt > 1) {
                    BrokerSpy brokerSpy = new BrokerSpy(broker_ip);
                    ArrayList<Exchange> exchangeList = brokerSpy.getAllExchangeMetadata();

                    ArrayList<MigrateThread> migrateList = new ArrayList<MigrateThread>();
                    for(Exchange exg : exchangeList) {
                        migrateList.add(new MigrateThread(exg));
                    }

                    while(migrateList.size() > 0) {
                        Iterator<MigrateThread> iter = migrateList.iterator();
                        ArrayList<MigrateThread> runningList = new ArrayList<MigrateThread>();
                        for(int i = 0; i < 8; i++) {
                            if(iter.hasNext()) {
                                MigrateThread th = iter.next();
                                th.start();
                                runningList.add(th);
                            }
                        }

                        ArrayList<MigrateThread> pendingList = (ArrayList<MigrateThread>)migrateList.clone();
                        for(MigrateThread th : runningList)
                            pendingList.remove(th);

                        for(;;) {
                            Utils.justSleep(2000);
                            boolean allReady = true;
                            for(MigrateThread th : runningList)
                                allReady &= th.isReady();
                            if(!allReady)
                                continue;

                            boolean hasAlive = false;
                            if(pendingList.size() > 0) {
                                System.out.print("pending to be migrated: ");
                                for(MigrateThread th : pendingList)
                                    System.out.print(th.getExchange() + ", ");
                                myConsole.logResponseNL("");
                            }
                            for(MigrateThread th : runningList) {
                                hasAlive |= th.isAlive();
                                if(th.isAlive()){
                                    if(th.getPendingCnt()>0)
                                        myConsole.logResponseNL("[%s] %d messages left", th.getExchange(), th.getPendingCnt());
                                }
                            }
                            if(!hasAlive)
                                break;
                        }

                        for(MigrateThread th : runningList) {
                            myConsole.logResponseNL("[" + th.getExchange() + "] Migration completed! " + th.getMigrateCount() + " messages forwarded");
                            th.join();
                            migrateList.remove(th);
                        }
                    }
                }
                myConsole.logResponse("stopping `%s' ... success%n", broker_ip);
            }
            catch(Exception e) {
                myConsole.logResponse("stopping `%s' ... failed%n", broker_ip);
                myConsole.logResponseNL(e.toString());
            }
            finally{
                try {
                    brokerLock.release();
                    if(brokerLock.getChildren().isEmpty())
                        brokerLock.delete();
                }
                catch(CODIException e) {
                }
            }
        }

        private void reserve(String [] argv) {
            if(argv.length == 1) {
                myConsole.logResponseNL("missing `flag'");
                return;
            }
            String broker_ip = argv[0];
            String flag = argv[1];
            if(!isValidBrokerIP(broker_ip)) {
                myConsole.logResponse("broker_ip `%s' not available%n", broker_ip);
                return;
            }

            ZooKeeperInfo.Broker broker = brokerFarm.getBrokerByHost(broker_ip);
            ZooKeeperInfo.Broker.Builder builder = ZooKeeperInfo.Broker.newBuilder();
            builder.mergeFrom(broker);
            if(flag.equals("true") || flag.equals("1"))
                builder.setReserved(true);
            else if(flag.equals("false") || flag.equals("0"))
                builder.setReserved(false);
            else {
                myConsole.logResponse("invalid flag `%s'%n", flag);
                return;
            }
            broker = builder.build();

            try {
                new ZNode("/broker/" + broker_ip).setContent(broker.toString().getBytes());
                myConsole.logResponse("broker `%s' reserved = %b%n", broker_ip, broker.getReserved());
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        ////////////////////////////////////////////////////////////////////////////////

        public BrokerCommand() {
            Option optHelp = new Option("h", "help", false, "display help message");
            Option optList = new Option("l", "list", false, "list all brokers and their states");
            Option optStart = new Option("s", "start", true, "start the broker");
            optStart.setArgName("broker_ip");
            Option optStop = new Option("t", "stop", true, "stop the broker");
            optStop.setArgName("broker_ip");
            Option optReserve = new Option("r", "reserve", true, "mark reserved brokers\n`flag' = {true|false}");
            optReserve.setArgName("broker_ip flag");
            optReserve.setArgs(2);

            opts.addOption(optList);
            opts.addOption(optStart);
            opts.addOption(optStop);
            opts.addOption(optReserve);
            opts.addOption(optHelp);
        }

        public void execute(String [] argv) {
            try {
                CommandLine line = new GnuParser().parse(opts, argv);
                if(line.hasOption("list"))
                    list();
                else if(line.hasOption("start"))
                    start(line.getOptionValue("start"));
                else if(line.hasOption("stop"))
                    stop(line.getOptionValue("stop"));
                else if(line.hasOption("reserve"))
                    reserve(line.getOptionValues("reserve"));
                else
                    help();
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        public void help() {
            HelpFormatter formatter = new HelpFormatter();
            formatter.setWidth(256);
            formatter.printHelp("broker", opts);
        }

        public String explain() {
            return "manage brokers";
        }

        public Options getOptions() {
            return opts;
        }
    }

    private class StatusCommand implements CommandExecutable {
        private Options opts = new Options();

        private void all() {
            zookeeper();
            mistd();
            bridge();
        }

        private void bridge() {
            try {
                myConsole.logResponseNL("==> all tme-bridge(s)");
                List<String> all_bridge = new ZNode("/bridge").getChildren();
                boolean showed = false;
                for(String b: all_bridge) {
                    List<String> masters = new ZNode("/bridge/" + b + "/master").getChildren();
                    List<String> slaves = new ZNode("/bridge/" + b + "/slave").getChildren();
                    if(masters.size() + slaves.size() > 0) {
                        myConsole.logResponse("    %s:%n", b);
                        for(String m: masters)
                            myConsole.logResponse("        %s (master)%n", m);
                        for(String m: slaves)
                            myConsole.logResponse("        %s (slave)%n", m);
                        showed = true;
                    }
                }
                if(!showed)
                    myConsole.logResponseNL("    no bridge(s)");
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        private void mistd() {
            try {
                myConsole.logResponseNL("==> all mistd clients");
                List<String> all_mistd = new ZNode("/local/mist_client").getChildren();
                if(all_mistd.size() == 0)
                    myConsole.logResponseNL("    no clients");
                else {
                    for(String m: all_mistd)
                        myConsole.logResponse("    %s%n", m);
                }
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        private boolean verifyZooKeeper(String host) {
            if(!Utils.checkSocketConnectable(host))
                return false;
            String zkPath = "/ZK_TEST_NODE";
            String zkData = "02a7e44daac8046f43de84b2546a4d63";
            try {
                ZNode node = new ZNode(zkPath);
                node.create(false, zkData.getBytes());
                String res = new String(node.getContent());
                node.delete();
                if(res.compareTo(zkData) == 0)
                    return true;
                else
                    return false;
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
                return false;
            }
        }

        private void zookeeper() {
            String [] servers = Daemon.propMIST.getProperty("mistd.zookeeper").split(",");
            myConsole.logResponseNL("==> all zookeeper servers");
            for(String host: servers)
                myConsole.logResponse("    %-25s %-20s%n", host, verifyZooKeeper(host) ? "available": "missing");
        }

        ////////////////////////////////////////////////////////////////////////////////

        public StatusCommand() {
            Option optHelp = new Option("h", "help", false, "display help message");
            Option optAll = new Option("a", "all", false, "list all status");
            Option optBridge = new Option("b", "bridge", false, "list tme-bridge status");
            Option optClient = new Option("m", "mistd", false, "list mistd status");
            Option optZooKeeper = new Option("z", "zookeeper", false, "list zookeeper status");

            opts.addOption(optAll);
            opts.addOption(optBridge);
            opts.addOption(optClient);
            opts.addOption(optZooKeeper);
            opts.addOption(optHelp);
        }

        public void execute(String [] argv) {
            try {
                CommandLine line = new GnuParser().parse(opts, argv);
                if(line.hasOption("all"))
                    all();
                else if(line.hasOption("bridge"))
                    bridge();
                else if(line.hasOption("mistd"))
                    mistd();
                else if(line.hasOption("zookeeper"))
                    zookeeper();
                else
                    help();
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        public void help() {
            HelpFormatter formatter = new HelpFormatter();
            formatter.setWidth(256);
            formatter.printHelp("status", opts);
        }

        public String explain() {
            return "show cluster status";
        }

        public Options getOptions() {
            return opts;
        }
}

    private class ConfigCommand implements CommandExecutable {
        private Options opts = new Options();
        private static final String ZNODE_PORTAL_DB = "/global/portal_db";
        private static final String ZNODE_MAIL_SMTP = "/global/mail_smtp";
        private static final String ZNODE_MAIL_ALERT = "/global/mail_alert";
        private static final String ZNODE_MAIL_SENDER = "/global/mail_sender";

        private boolean checkDBConnection(ZooKeeperInfo.PortalDB db) {
            String connectionURL = String.format("jdbc:mysql://%s:%s/%s?user=%s&password=%s", db.getHost(), db.getPort(), db.getName(), db.getUser(), db.getPassword());
            try {
                Class.forName("com.mysql.jdbc.Driver");
                java.sql.Connection conn = DriverManager.getConnection(connectionURL);
                conn.close();
                return true;
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
                return false;
            }
        }

        private void portaldb(String connectString) {
            String [] v = connectString.split(":");
            if(v.length < 5) {
                myConsole.logResponseNL("connect string `%s' not valid", connectString);
                return;
            }

            String host = String.format("%s:%s", v[0], v[1]);
            if(!Utils.checkSocketConnectable(host)) {
                myConsole.logResponseNL("`%s' not connectable, please check out ip or port", host);
                return;
            }

            ZooKeeperInfo.PortalDB.Builder p_builder = ZooKeeperInfo.PortalDB.newBuilder();
            p_builder.setHost(v[0]).setPort(v[1]);
            p_builder.setUser(v[2]).setPassword(v[3]);
            p_builder.setName(v[4]);
            ZooKeeperInfo.PortalDB pdb = p_builder.build();

            if(!checkDBConnection(pdb)) {
                myConsole.logResponseNL("`%s' not connectable, please check out account for db", connectString);
                return;
            }

            createAndSetNode(ZNODE_PORTAL_DB, pdb.toString().getBytes());
            myConsole.logResponseNL("%s => %s", ZNODE_PORTAL_DB, "{ " + pdb.toString().replace("\n", "; ") + " }");
        }

        private void unportaldb() {
            if(deleteNode(ZNODE_PORTAL_DB))
                myConsole.logResponseNL("`%s' removed", ZNODE_PORTAL_DB);
            else
                myConsole.logResponseNL("no previous setting of `%s'", ZNODE_PORTAL_DB);
        }

        private void smtp(String server) {
            try {
                String host = server + ":25";
                if(!Utils.checkSocketConnectable(host)) {
                    myConsole.logResponse("`%s' not connectable%n", server);
                    return;
                }

                createAndSetNode(ZNODE_MAIL_SMTP, server.getBytes());
                myConsole.logResponseNL("%s => %s", ZNODE_MAIL_SMTP, server);
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        private void unsmtp() {
            if(deleteNode(ZNODE_MAIL_SMTP))
                myConsole.logResponseNL("`%s' removed", ZNODE_MAIL_SMTP);
            else
                myConsole.logResponseNL("no previous setting of `%s'", ZNODE_MAIL_SMTP);
        }

        private void alert(String mail_list) {
            createAndSetNode(ZNODE_MAIL_ALERT, mail_list.getBytes());
            myConsole.logResponseNL("%s => %s", ZNODE_MAIL_ALERT, mail_list);
        }

        private void unalert() {
            if(deleteNode(ZNODE_MAIL_ALERT))
                myConsole.logResponseNL("`%s' removed", ZNODE_MAIL_ALERT);
            else
                myConsole.logResponseNL("no previous setting of `%s'", ZNODE_MAIL_ALERT);
        }
        
        private void sender(String mail_sender) {
            createAndSetNode(ZNODE_MAIL_SENDER, mail_sender.getBytes());
            myConsole.logResponseNL("%s => %s", ZNODE_MAIL_SENDER, mail_sender);
        }

        private void unsender() {
            if(deleteNode(ZNODE_MAIL_SENDER))
                myConsole.logResponseNL("`%s' removed", ZNODE_MAIL_SENDER);
            else
                myConsole.logResponseNL("no previous setting of `%s'", ZNODE_MAIL_SENDER);
        }

        private void list() {
            ArrayList<String> configs = new ArrayList<String>();
            configs.add(ZNODE_PORTAL_DB);
            configs.add(ZNODE_MAIL_SMTP);
            configs.add(ZNODE_MAIL_ALERT);
            configs.add(ZNODE_MAIL_SENDER);

            boolean showed = false;
            for(String key: configs) {
                try {
                    String value = new String(new ZNode(key).getContent()).trim();
                    if(value.length() > 0) {
                        if(key.equals(ZNODE_PORTAL_DB))
                            myConsole.logResponseNL("%-24s => %s", key, "{ " + value.replace("\n", "; ") + " }");
                        else
                            myConsole.logResponseNL("%-24s => %s", key, value);
                        showed = true;
                    }
                }
                catch(Exception e) {
                }
            }
            if(!showed)
                myConsole.logResponseNL("no config(s)");
        }

        ////////////////////////////////////////////////////////////////////////////////

        public ConfigCommand() {
            Option optHelp = new Option("h", "help", false, "display help message");
            Option optPortalDB = new Option("p", "portal-db", true, "specify Portal DB\n`connect_string' = host:port:user:password:db_name");
            optPortalDB.setArgName("connect_string");
            Option optUnPortalDB = new Option("P", "un-portal-db", false, "remove Portal DB setting");
            Option optList = new Option("l", "list", false, "list all configurations");
            Option optSmtp = new Option("m", "mail-smtp", true, "set SMTP mail server");
            optSmtp.setArgName("server");
            Option optUnSmtp = new Option("M", "un-mail-smtp", false, "remove SMTP mail server");
            Option optAlert = new Option("a", "mail-alert", true, "set mail alert recipients\n`mail_list' = user1@host;user2@host");
            optAlert.setArgName("mail_list");
            Option optUnAlert = new Option("A", "un-mail-alert", false, "remove mail alert recipients");
            Option optSender = new Option("s", "mail-sender", true, "set mail alert sender\n`mail_sender' = user1@host");
            Option optUnSender = new Option("S", "un-mail-sender", false, "remove mail alert sender");
            optSender.setArgName("sender_mail");

            opts.addOption(optPortalDB);
            opts.addOption(optUnPortalDB);
            opts.addOption(optHelp);
            opts.addOption(optList);
            opts.addOption(optSmtp);
            opts.addOption(optUnSmtp);
            opts.addOption(optAlert);
            opts.addOption(optUnAlert);
            opts.addOption(optSender);
            opts.addOption(optUnSender);
        }

        public void execute(String [] argv) {
            try {
                CommandLine line = new GnuParser().parse(opts, argv);
                if(line.hasOption("portal-db"))
                    portaldb(line.getOptionValue("portal-db"));
                else if(line.hasOption("un-portal-db"))
                    unportaldb();
                else if(line.hasOption("mail-smtp"))
                    smtp(line.getOptionValue("mail-smtp"));
                else if(line.hasOption("un-mail-smtp"))
                    unsmtp();
                else if(line.hasOption("mail-alert"))
                    alert(line.getOptionValue("mail-alert"));
                else if(line.hasOption("un-mail-alert"))
                    unalert();
                else if(line.hasOption("mail-sender"))
                    sender(line.getOptionValue("mail-sender"));
                else if(line.hasOption("un-mail-sender"))
                    unsender();
                else if(line.hasOption("list"))
                    list();
                else
                    help();
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        public void help() {
            HelpFormatter formatter = new HelpFormatter();
            formatter.setWidth(256);
            formatter.printHelp("config", opts);
        }

        public String explain() {
            return "global configuration";
        }

        public Options getOptions() {
            return opts;
        }
    }

    private class ExchangeCommand implements CommandExecutable {
        private Options opts = new Options();

        private void list() {
            List<ExchangeFarm.ExchangeInfo> exchanges = ExchangeFarm.getAllExchanges();
            myConsole.logResponse("%d exchanges%n", exchanges.size());
            if(exchanges.size() > 0) {
                Table tab = new Table(3);
                tab.addCell("Exchange");
                tab.addCell("Host");
                tab.addCell("Clients");
                for(ExchangeFarm.ExchangeInfo info: exchanges) {
                    tab.addCell(info.name);
                    tab.addCell(info.host);
                    tab.addCell(String.valueOf(info.refCount), numberStyle);
                }
                myConsole.logResponseNL(tab.render());
            }

            try {
                Table tab = new Table(3);
                tab.addCell("Exchange");
                tab.addCell("Config. Type");
                tab.addCell("Remark");
                
                List<String> fixed_ex = new ZNode(ZNODE_FIXED).getChildren();
                for(String name: fixed_ex) {
                    ZooKeeperInfo.Exchange.Builder builder = ZooKeeperInfo.Exchange.newBuilder();
                    TextFormat.merge(new String(new ZNode(ZNODE_FIXED + "/" + name).getContent()), builder);
                    tab.addCell("{queue,topic}:" + name);
                    tab.addCell("Fixed", new CellStyle(HorizontalAlign.center));
                    tab.addCell(builder.build().getHost());
                }
                
                List<String> drop_ex = new ZNode(ZNODE_DROP).getChildren();
                for(String name : drop_ex) {
                    ZooKeeperInfo.DropConfig.Builder drop_builder = ZooKeeperInfo.DropConfig.newBuilder();
                    TextFormat.merge(new String(new ZNode(ZNODE_DROP + "/" + name).getContent()), drop_builder);
                    tab.addCell(name);
                    tab.addCell("Drop Polocy", new CellStyle(HorizontalAlign.center));
                    tab.addCell(drop_builder.build().getPolicy() == DropConfig.Policy.NEWEST ? "newest": "oldest");
                }
                
                List<String> limit_ex = new ZNode(ZNODE_LIMIT).getChildren();
                for(String name : limit_ex) {
                    ZooKeeperInfo.TotalLimit.Builder limit_builder = ZooKeeperInfo.TotalLimit.newBuilder();
                    TextFormat.merge(new String(new ZNode(ZNODE_LIMIT + "/" + name).getContent()), limit_builder);
                    tab.addCell(name);
                    tab.addCell("Maximum limit", new CellStyle(HorizontalAlign.center));
                    ZooKeeperInfo.TotalLimit limit = limit_builder.build();
                    tab.addCell(String.format("%d bytes / %d messages", limit.getSizeBytes(), limit.getCount()));
                }

                List<String> alert_limit_ex = new ZNode(ZNODE_ALERT_LIMIT).getChildren();
                for(String name : alert_limit_ex) {
                    ZooKeeperInfo.AlertConfig.Builder alert_limit_builder = ZooKeeperInfo.AlertConfig.newBuilder();
                    TextFormat.merge(new ZNode(ZNODE_ALERT_LIMIT + "/" + name).getContentString(), alert_limit_builder);
                    ZooKeeperInfo.AlertConfig alertConfig = alert_limit_builder.build();
                    tab.addCell(name);
                    tab.addCell("Alert limit", new CellStyle(HorizontalAlign.center));
                    tab.addCell(String.format("%d messages to %s", alertConfig.getCount(), alertConfig.getReceiver()));
                }

                myConsole.logResponse("%d configured exchanges%n", fixed_ex.size() + drop_ex.size() + limit_ex.size());
                if(fixed_ex.size() + drop_ex.size() + limit_ex.size() > 0)
                    myConsole.logResponseNL(tab.render());
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        private void fix(String [] argv) {
            if(argv.length == 1) {
                myConsole.logResponse("exchange: `%s', missing broker_ip%n", argv[0]);
                return;
            }
            Exchange ex = new Exchange(argv[0]);
            String broker_ip = argv[1];
            if(!isValidBrokerIP(broker_ip)) {
                myConsole.logResponse("broker_ip `%s' not available%n", broker_ip);
                return;
            }

            ZooKeeperInfo.Exchange.Builder builder = ZooKeeperInfo.Exchange.newBuilder();
            builder.setHost(broker_ip);
            ZooKeeperInfo.Exchange ex_data = builder.build();

            String path = String.format("/global/fixed_exchange/%s", ex.getName());
            createAndSetNode(path, ex_data.toString().getBytes());
            myConsole.logResponse("exchange `%s' fixed on broker `%s'%n", ex.getName(), broker_ip);
        }

        private void unfix(String exchange) {
            Exchange ex = new Exchange(exchange);
            String path = String.format("/global/fixed_exchange/%s", ex.getName());
            if(deleteNode(path))
                myConsole.logResponse("fixed exchange `%s' removed%n", ex.getName());
            else
                myConsole.logResponse("fixed exchange `%s' not found%n", ex.getName());
        }

        public void migrate(String exchange) {
            MigrateThread th = new MigrateThread(new Exchange(exchange));
            th.start();
            while(th.isAlive()) {
                if(th.getPendingCnt() > 0)
                    myConsole.logResponseNL("[" + exchange + "] " + th.getPendingCnt() + " messages left");
                Utils.justSleep(2000);
            }
            myConsole.logResponseNL("Migration completed! " + th.getMigrateCount() + " messages forwarded");
            try {
                th.join();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
            }
        }

        private void drop(String policy) {
            String strArray[] = policy.split(":");
            if(strArray.length != 2) {
                help();
                return;
            }
            if(strArray[1].compareTo("newest") != 0 && strArray[1].compareTo("oldest") != 0) {
                System.out.println("Drop policy should be either [newest] or [oldest]!");
                return;
            }
            Exchange ex = new Exchange(strArray[0]);
            String path = ZNODE_DROP + "/" + ex.getName();
            ZooKeeperInfo.DropConfig dropConfig = ZooKeeperInfo.DropConfig.newBuilder().setPolicy(strArray[1].compareTo("newest") == 0 ? DropConfig.Policy.NEWEST: DropConfig.Policy.OLDEST).build();
            createAndSetNode(path, dropConfig.toString().getBytes());
            
            try {
                if(dropConfig.getPolicy().equals(ZooKeeperInfo.DropConfig.Policy.NEWEST))
                    BrokerSpy.setExchangeFlowControl(ex, ExchangeFarm.FlowControlBehavior.DROP_NEWEST);
                else
                    BrokerSpy.setExchangeFlowControl(ex, ExchangeFarm.FlowControlBehavior.DROP_OLDEST);
            }
            catch(Exception e) {
            }
        }

        private void block(String exchange) {
            Exchange ex = new Exchange(exchange);
            String path = ZNODE_DROP + "/" + ex.getName();
            if(deleteNode(path))
                System.out.printf(String.format("drop policy on `%s' removed%n", ex.getName()));
            else
                System.out.printf(String.format("`%s' not found%n", ex.getName()));
            try {
                BrokerSpy.setExchangeFlowControl(ex, ExchangeFarm.FlowControlBehavior.BLOCK);
            }
            catch(Exception e) {
            }
        }
        
        private void limit(String limitStr) {
            if(!limitStr.matches(".*:\\d+:\\d+")) {
                help();
                return;
            }
            String strArray[] = limitStr.split(":");
            Exchange ex = new Exchange(strArray[0]);
            long size = Long.valueOf(strArray[1]);
            long count = Long.valueOf(strArray[2]);

            ZooKeeperInfo.TotalLimit limitConfig = ZooKeeperInfo.TotalLimit.newBuilder().setSizeBytes(size).setCount(count).build();
            String path = ZNODE_LIMIT + "/" + ex.getName();
            createAndSetNode(path, limitConfig.toString().getBytes());

            try {
                BrokerSpy.setExchangeTotalLimit(ex, size, count);
            }
            catch(Exception e) {
            }
        }

        private void defLimit(String exchange) {
            Exchange ex = new Exchange(exchange);
            String path = ZNODE_LIMIT + "/" + ex.getName();
            if(deleteNode(path))
                System.out.printf(String.format("limit on `%s' changed to default%n", ex.getName()));
            else
                System.out.printf(String.format("`%s' not found%n", ex.getName()));

            try {
                BrokerSpy.setExchangeTotalLimit(ex, 10485760L, 100000);
            }
            catch(Exception e) {
            }
        }

        private void alertLimit(String limitStr) {
            if(!limitStr.matches(".*:\\d+:.*")) {
                help();
                return;
            }
            String strArray[] = limitStr.split(":");
            Exchange ex = new Exchange(strArray[0]);
            long count = Long.valueOf(strArray[1]);
            String receiver = strArray.length == 3 ? strArray[2]: "";

            String path = ZNODE_ALERT_LIMIT + "/" + ex.getName();
            if(count <= 0) {
                deleteNode(path);
            }
            else {
                ZooKeeperInfo.AlertConfig.Builder alertConfigBuilder = ZooKeeperInfo.AlertConfig.newBuilder();
                alertConfigBuilder.setCount(count);
                if(!receiver.isEmpty()) {
                    alertConfigBuilder.setReceiver(receiver);
                }
                createAndSetNode(path, alertConfigBuilder.build().toString().getBytes());
            }
        }

        ////////////////////////////////////////////////////////////////////////////////

        public static final String ZNODE_FIXED = "/global/fixed_exchange";
        public static final String ZNODE_DROP = "/global/drop_exchange";
        public static final String ZNODE_LIMIT = "/global/limit_exchange";
        public static final String ZNODE_ALERT_LIMIT = "/global/alert_limit_exchange";

        public ExchangeCommand() {
            Option optHelp = new Option("h", "help", false, "display help message");
            Option optList = new Option("l", "list", false, "list all exchanges");
            Option optMigrate = new Option("m", "migrate", true, "migrate the exchange to another broker");
            optMigrate.setArgName("exchange");
            Option optFix = new Option("f", "fix", true, "specify an exchange with a fixed broker");
            optFix.setArgName("exchange broker_ip");
            optFix.setArgs(2);
            Option optUnFix = new Option("F", "un-fix", true, "remove fixed exchange");
            optUnFix.setArgName("exchange");
            Option optDrop = new Option("d", "drop", true, "make exchange to drop [policy] message when full");
            optDrop.setArgName("policy");
            Option optBlock = new Option("b", "block", true, "make exchange to block sending when full");
            optBlock.setArgName("exchange");
            Option optLimit = new Option("L", "limit", true, "exchange's maximum total message count and size limit");
            optLimit.setArgName("limit");
            Option optDefaultLimit = new Option("D", "default-limit", true, "change the size and count limit of a exchange back to default (10M/100000)");
            optDefaultLimit.setArgName("exchange");
            Option optAlertLimit = new Option("a", "alert-limit", true, "change the limit that triggers the alert when the exchange is not consumed, set to 0 will alert when there is any pending message, receiver is seperated by ; and overrides the default mail receiver");
            optAlertLimit.setArgName("alert-limit");

            opts.addOption(optList);
            opts.addOption(optMigrate);
            opts.addOption(optFix);
            opts.addOption(optUnFix);
            opts.addOption(optHelp);
            opts.addOption(optDrop);
            opts.addOption(optBlock);
            opts.addOption(optLimit);
            opts.addOption(optDefaultLimit);
            opts.addOption(optAlertLimit);
        }

        public void execute(String [] argv) {
            try {
                CommandLine line = new GnuParser().parse(opts, argv);
                if(line.hasOption("list"))
                    list();
                else if(line.hasOption("fix"))
                    fix(line.getOptionValues("fix"));
                else if(line.hasOption("un-fix"))
                    unfix(line.getOptionValue("un-fix"));
                else if(line.hasOption("migrate"))
                    migrate(line.getOptionValue("migrate"));
                else if(line.hasOption("drop"))
                    drop(line.getOptionValue("drop"));
                else if(line.hasOption("block"))
                    block(line.getOptionValue("block"));
                else if(line.hasOption("limit"))
                    limit(line.getOptionValue("limit"));
                else if(line.hasOption("default-limit"))
                    defLimit(line.getOptionValue("default-limit"));
                else if(line.hasOption("alert-limit"))
                    alertLimit(line.getOptionValue("alert-limit"));
                else
                    help();
            }
            catch(Exception e) {
                myConsole.logResponseNL(e.getMessage());
            }
        }

        public void help() {
            HelpFormatter formatter = new HelpFormatter();
            formatter.setWidth(256);
            formatter.printHelp("exchange", opts);
            myConsole.logResponseNL("notations: ");
            myConsole.logResponseNL(" `exchange' = {queue|topic}:EXCHANGENAME");
            myConsole.logResponseNL(" `policy' = EXCHANGENAME:[newest|oldest]");
            myConsole.logResponseNL(" `limit' = EXCHANGENAME:size:count");
            myConsole.logResponseNL(" `alert-limit' = EXCHANGENAME:count:receiver");
        }

        public String explain() {
            return "manage exchanges";
        }

        public Options getOptions() {
            return opts;
        }
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                myApp.shutdown();
            }
        });
        myConsole.logResponse("Welcome to the TME 2.0 console!%n%n");
        myConsole.logResponse("Connected to `%s'%n%n", Daemon.propMIST.getProperty("mistd.zookeeper"));
        myConsole.logResponse("Type `help' for help message, `exit' to exit.%n");
    }

    private void shutdown() {
        if(consoleLock != null){
            try {
                consoleLock.release();
                if(consoleLock.getChildren().isEmpty())
                    consoleLock.delete();
            }
            catch(CODIException e){
            }
        }
        myConsole.logResponse("Bye-bye!%n");
    }

    private boolean isValidBrokerIP(String broker_ip) {
        String path = String.format("/broker/%s", broker_ip);
        try {
            if(new ZNode(path).exists())
                return true;
        }
        catch(CODIException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean createAndSetNode(String path, byte[] data) {
        ZNode node = new ZNode(path);
        try {
            if(!node.exists()) {
                node.create(false, data);
                return true;
            }
            else {
                node.setContent(data);
                return true;
            }

        }
        catch(Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean deleteNode(String path) {
        ZNode node = new ZNode(path);
        try {
            if(!node.exists())
                return false;
            node.delete();
        }
        catch(CODIException e) {
            e.printStackTrace();
        }
        return true;
    }

    ////////////////////////////////////////////////////////////////////////////////

    public TmeConsole() {
        myConsole.addCommand("broker", new BrokerCommand());
        myConsole.addCommand("exchange", new ExchangeCommand());
        myConsole.addCommand("config", new ConfigCommand());
        myConsole.addCommand("status", new StatusCommand());
    }

    public void start() {
        if(loggedUser != null)
            myConsole.setLogUser(loggedUser);
        myConsole.launch();
    }

    public static void main(String [] argv) {
        boolean locked = false;
        try {
            locked = authenticateLock();
            brokerFarm = new BrokerFarm();
            myApp = new TmeConsole();
            myApp.addShutdownHook();
            myApp.start();
        }
        catch(CODIException coe) {
            myConsole.logResponseNL("User or password is wrong. Exit now!");
        }
        catch(Exception e) {
            myConsole.logResponseNL(e.getMessage());
        }
        finally {
            System.exit(authenticateUnlock(locked));
        }
    }
    
    private static final String ACL_NODE = "/global/acl/console_admins";

    private static boolean authenticateLock() throws Exception {
        ZKSessionManager.initialize(Daemon.propMIST.getProperty("mistd.zookeeper") + Daemon.propMIST.getProperty("mistd.zookeeper.tmeroot"), Integer.valueOf(Daemon.propMIST.getProperty("mistd.zookeeper.timeout")));
        ZKSessionManager zksm = ZKSessionManager.instance();
        zksm.waitConnected();

        ZNode authNode = new ZNode(ACL_NODE);
        if(authNode.exists()) {
            LoginContext lc = new LoginContext("ldaploginmodule", new CallbackHandler() {

                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    for(Callback cb : callbacks) {
                        if(cb instanceof NameCallback) {
                            System.out.print("Enter username: ");
                            ((NameCallback) cb).setName(System.console().readLine());
                        }
                        else if(cb instanceof PasswordCallback) {
                            System.out.print("Enter password: ");
                            ((PasswordCallback) cb).setPassword(System.console().readPassword());
                        }
                    }
                }
            });
            lc.login();

            boolean authorized = false;
            for(String admin : authNode.getContentString().split(",")) {
                for(Principal p : lc.getSubject().getPrincipals()) {
                    if(p instanceof UserPrincipal) {
                        if(p.getName().equals(admin.trim())) {
                            authorized = true;
                        }
                    }
                }
            }
            if(!authorized)
                throw new Exception("You are not authorized to console, please contact with operation");
        }
        else
            myConsole.logResponseNL("Warning: Can't get authorization information, running under unprotected mode!\n");

        zksm.setDefaultPerms(Id.ANYONE, EnumSet.allOf(Perms.class));

        String lockPath = "/global/tme-console.lock";
        consoleLock = new ZLock(lockPath);

        if(consoleLock.tryAcquire(LockType.WRITE_LOCK, 3000) == false)
            throw new Exception("Cannot get administration lock! Another instance of tme-console might be running, exiting.");
        
        return true;
    }

    private static int authenticateUnlock(boolean locked) {
        if(!locked) {
            if(consoleLock != null) {
                try {
                    consoleLock.release();
                    if(consoleLock.getChildren().isEmpty())
                        consoleLock.delete();
                }
                catch(CODIException e) {
                }
            }
            return 1;
        }
        return 0;
    }
}

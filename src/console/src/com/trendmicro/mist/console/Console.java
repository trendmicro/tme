
package com.trendmicro.mist.console;

import java.io.File;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.trendmicro.spn.common.util.Utils;

import jline.ArgumentCompletor;
import jline.Completor;
import jline.ConsoleReader;
import jline.History;
import jline.NullCompletor;
import jline.SimpleCompletor;

public class Console {
    class IdleMonitor extends Thread {
        private boolean run = true;
        private long idleSince = -1;

        public void kill() {
            run = false;
        }

        public synchronized void setIdle() {
            idleSince = System.currentTimeMillis();
        }

        public synchronized void setBusy() {
            idleSince = -1;
        }

        @Override
        public void run() {
            while(run) {
                if(idleSince != -1) {
                    synchronized(this) {
                        // if 30 min idle time reached, exit
                        if((System.currentTimeMillis() - idleSince) / 1000 > 30 * 60) {
                            System.out.println("idle timeout reached, exiting...");
                            System.exit(0);
                        }
                    }
                }
                Utils.justSleep(1000);
            }
        }
    }
    
    private String commandPrompt;
    private HashMap<String, CommandExecutable> commandTable = new HashMap<String, CommandExecutable>();
    private Completor completor = null;
    private String currentUser;
    private String pathLog;
    private BufferedWriter logStream = null;

    public interface CommandListener {
        public void processCommand(String command);
    }

    private CommandListener cmdListener = null;

    private class ExitCommand implements CommandExecutable {
        public void execute(String [] argv) {
            System.exit(0);
        }
        public void help() {
            logResponse("usage: exit%n");
            logResponse("    exit from the program%n");
        }
        public String explain() {
            return "exit program";
        }
        public Options getOptions() {
            return null;
        }
    }

    private class HelpCommand implements CommandExecutable {
        public void execute(String [] argv) {
            if(argv.length == 1) {
                logResponse("commands:%n");
                for(Map.Entry<String, CommandExecutable> e: commandTable.entrySet())
                    logResponse(" %-20s %s%n", e.getKey(), e.getValue().explain());
                return;
            }
            if(commandTable.containsKey(argv[1]))
                commandTable.get(argv[1]).help();
            else
                logResponse("Don't know how to help on `%s'.%n", argv[1]);
        }
        public void help() {
            logResponse("usage: help <command>%n");
            logResponse("    list all commands or get help message for specific command%n");
        }
        public String explain() {
            return "getting help";
        }
        public Options getOptions() {
            return null;
        }
    }

    private ConsoleReader prepareReader() throws IOException {
        ConsoleReader reader = new ConsoleReader();
        reader.setUseHistory(true);
        Map<String, String> env = System.getenv();
        String fileHistory = String.format("%s/.%s.history", env.containsKey("HOME") ? env.get("HOME"): "/tmp", commandPrompt);
        reader.setHistory(new History(new File(fileHistory)));
        return reader;
    }

    ////////////////////////////////////////////////////////////////////////////////

    public Console(String prompt) {
        commandPrompt = prompt;
        commandTable.put("help", new HelpCommand());
        commandTable.put("exit", new ExitCommand());
        
        Map<String, String> env = System.getenv();
        currentUser = env.containsKey("USER") ? env.get("USER"): "Unknown";
        pathLog = String.format("%s/.%s.log", env.containsKey("HOME") ? env.get("HOME"): "/tmp", commandPrompt);
        try {
            logStream = new BufferedWriter(new FileWriter(pathLog, true));
        }
        catch(IOException e) {
            System.out.println(e.getMessage());
        }
    }

    protected void finalize () throws Throwable {
        if(logStream != null)
            logStream.close();
    }
    
    public void setLogUser(String user) {
    	currentUser = user;
    }

    public void setCommandListener(CommandListener listener) {
        cmdListener = listener;
    }

    public void setCompletor(Completor cptr) {
        completor = cptr;
    }

    public static String [] getAllCommands(Map<String, CommandExecutable> cmdTable) {
        String [] cmds = new String[cmdTable.size()];
        cmds = cmdTable.keySet().toArray(cmds);
        return cmds;
    }

    public static String [] getAllArguments(Map<String, CommandExecutable> cmdTable) {
        HashMap<String, Boolean> allArgs = new HashMap<String, Boolean>();
        for(Map.Entry<String, CommandExecutable> e: cmdTable.entrySet()) {
            Options opts = e.getValue().getOptions();
            if(opts == null)
                continue;
            for(Object obj: opts.getOptions()) {
                Option o = (Option) obj;
                allArgs.put("--" + o.getLongOpt(), true);
            }
        }
        String [] args = new String[allArgs.size()];
        args = allArgs.keySet().toArray(args);
        return args;
    }

    public static Completor extractCompletor(Map<String, CommandExecutable> cmdTable) {
        SimpleCompletor cmdCompletor = new SimpleCompletor(getAllCommands(cmdTable));
        SimpleCompletor argCompletor = new SimpleCompletor(getAllArguments(cmdTable));
        return new ArgumentCompletor(new Completor [] { cmdCompletor, argCompletor, new NullCompletor() });
    }
    
    public void logCommand(String commandLine) {
        try {
            String str = String.format("[%s] %s: %s\n", Calendar.getInstance().getTime(), currentUser, commandLine);
            logStream.write(str);
            logStream.flush();
        }
        catch(IOException e) {
            System.err.println(e.getMessage());
        }
    }

    public void logResponse(String formatter, Object... objects) {
        try {
            System.out.printf(formatter, objects);
            logStream.write(String.format(formatter, objects));
            logStream.flush();
        }
        catch(IOException e) {
            System.err.println(e.getMessage());
        }
    }

    public void logResponseNL(String formatter, Object... objects) {
        try {
            System.out.printf(formatter + "\n", objects);
            logStream.write(String.format(formatter + "\n", objects));
            logStream.flush();
        }
        catch(IOException e) {
            System.err.println(e.getMessage());
        }
    }

    public void receiveCommand() {
        IdleMonitor idleMonitor = new IdleMonitor();
        idleMonitor.start();
        try {
            ConsoleReader reader = prepareReader();
            // allows caller to customize completor
            if(completor != null)
                reader.addCompletor(completor);
            do {
                idleMonitor.setIdle();
                String line = reader.readLine(commandPrompt + "> ").trim();
                // does not dispatch commands for caller, notify caller for processing commands
                if(cmdListener != null) {
                    idleMonitor.setBusy();
                    logCommand(line);
                    cmdListener.processCommand(line);
                }
            } while(true);
        }
        catch(NullPointerException e) {
            logResponseNL("exit");
        }
        catch(Exception e) {
            logResponseNL(e.getMessage());
        }
        finally {
            idleMonitor.kill();
            try {
                idleMonitor.join();
            }
            catch(InterruptedException e) {
            }
        }
    }

    public void launch() {
        IdleMonitor idleMonitor = new IdleMonitor();
        try {
            ConsoleReader reader = prepareReader();
            // automatically extract completor from commandTable
            completor = extractCompletor(commandTable);
            reader.addCompletor(completor);

            idleMonitor.start();
            do {
                idleMonitor.setIdle();
                String line = reader.readLine(commandPrompt + "> ").trim();
                StringTokenizer tok = new StringTokenizer(line);
                if(tok.hasMoreElements()) {
                    String cmd = tok.nextToken();
                    // dispatches commands based on commandTable
                    if(commandTable.containsKey(cmd)) {
                        idleMonitor.setBusy();
                        logCommand(line);
                        commandTable.get(cmd).execute(line.split("\\s+"));
                    }
                    else {
                        logCommand(line);
                        logResponse("Unknown command `%s'.%n", cmd);
                    }
                }
            } while(true);
        }
        catch(NullPointerException e) {
            logResponseNL("exit");
        }
        catch(Exception e) {
            logResponseNL(e.getMessage());
        }
        finally {
            idleMonitor.kill();
            try {
                idleMonitor.join();
            }
            catch(InterruptedException e) {
            }
        }
    }

    public void addCommand(String name, CommandExecutable cmd) {
        commandTable.put(name, cmd);
    }
}

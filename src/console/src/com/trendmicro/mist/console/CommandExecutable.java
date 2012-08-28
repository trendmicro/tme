package com.trendmicro.mist.console;

import org.apache.commons.cli.Options;

public interface CommandExecutable {
    public void execute(String[] argv);
    public void help();
    public String explain();
    public Options getOptions();
}

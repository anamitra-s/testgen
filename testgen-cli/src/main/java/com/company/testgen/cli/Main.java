package com.company.testgen.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Entry point for TestGen CLI (§6.1, §9.2).
 */
@Command(
        name = "testgen",
        description = "TestGen automated test generation CLI",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        subcommands = {
                PushCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class Main implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}

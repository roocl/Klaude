package io.klaude.cli;

import java.util.Arrays;

record CliArguments(String command, String goal) {
    // 解析第一阶段 CLI 命令及其参数
    static CliArguments parse(String[] args) {
        if (args.length == 0) {
            return new CliArguments("help", "");
        }
        String command = args[0];
        if (command.equals("ping") || command.equals("chat") || command.equals("help")
                || command.equals("trace")) {
            if (args.length != 1) {
                throw new IllegalArgumentException(command + " does not accept arguments");
            }
            return new CliArguments(command, "");
        }
        if (command.equals("cancel")) {
            if (args.length != 2 || args[1].isBlank()) {
                throw new IllegalArgumentException("cancel requires <run-id>");
            }
            return new CliArguments(command, args[1]);
        }
        if (!command.equals("run")) {
            throw new IllegalArgumentException("unknown command: " + command);
        }
        int goalIndex = Arrays.asList(args).indexOf("--goal");
        if (goalIndex < 0 || goalIndex == args.length - 1) {
            throw new IllegalArgumentException("run requires --goal <text>");
        }
        if (goalIndex != 1) {
            throw new IllegalArgumentException("unexpected run arguments");
        }
        String goal = String.join(" ", Arrays.copyOfRange(args, goalIndex + 1, args.length)).strip();
        if (goal.isEmpty()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        return new CliArguments(command, goal);
    }
}

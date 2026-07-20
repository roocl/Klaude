package io.klaude.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FixtureRoundTripMain {
    // 禁止实例化 fixture round-trip 工具类
    private FixtureRoundTripMain() {
    }

    // 读取命令和事件 fixtures，经 Java 模型往返后写入输出目录
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected contract root and output root");
        }
        Path contractRoot = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        Files.createDirectories(outputRoot);
        ObjectMapper mapper = ProtocolJson.mapper();
        roundTrip(
                mapper,
                contractRoot.resolve("fixtures/commands.jsonl"),
                outputRoot.resolve("commands.jsonl"),
                Command.class);
        roundTrip(
                mapper,
                contractRoot.resolve("fixtures/events.jsonl"),
                outputRoot.resolve("events.jsonl"),
                Event.class);
    }

    // 逐行解析指定协议类型并用 UTF-8 JSONL 写回
    private static void roundTrip(
            ObjectMapper mapper,
            Path input,
            Path output,
            Class<?> protocolType) throws IOException {
        List<String> outputLines;
        try (var lines = Files.lines(input, StandardCharsets.UTF_8)) {
            outputLines = lines
                    .map(line -> serialize(mapper, line, protocolType))
                    .toList();
        }
        Files.writeString(
                output,
                String.join("\n", outputLines) + "\n",
                StandardCharsets.UTF_8);
    }

    // 将一行 fixture 通过指定 Java 协议模型序列化
    private static String serialize(ObjectMapper mapper, String line, Class<?> protocolType) {
        try {
            Object value = mapper.readValue(line, protocolType);
            return mapper.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalArgumentException("invalid fixture line", error);
        }
    }
}

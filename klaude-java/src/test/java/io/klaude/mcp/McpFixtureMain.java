package io.klaude.mcp;

import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class McpFixtureMain {
    // 禁止实例化 fake MCP process 入口
    private McpFixtureMain() {
    }

    // 服务 initialize、list 和 call，并写大量 stderr 验证 drain
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "normal" : args[0];
        try (var reader = new BufferedReader(new InputStreamReader(
                     System.in, StandardCharsets.UTF_8));
             var writer = new OutputStreamWriter(System.out, StandardCharsets.UTF_8)) {
            for (int index = 0; index < 2_000; index++) {
                System.err.println("diagnostic-" + index + "-" + "x".repeat(100));
            }
            System.err.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                var request = ProtocolJson.mapper().readTree(line);
                if (mode.equals("eof")) {
                    return;
                }
                if (mode.equals("timeout")) {
                    Thread.sleep(60_000);
                    return;
                }
                if (!request.has("id")) {
                    continue;
                }
                String method = request.path("method").asText();
                String result = switch (method) {
                    case "initialize" -> "{}";
                    case "tools/list" -> "{\"tools\":[{\"name\":\"echo\","
                            + "\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}]}";
                    case "tools/call" -> mode.equals("error")
                            ? null
                            : "{\"content\":[{\"type\":\"text\",\"text\":\"stdio ok\"}]}";
                    default -> "{}";
                };
                if (result == null) {
                    writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id")
                            + ",\"error\":{\"code\":-32001,\"message\":\"fixture error\"}}\n");
                } else {
                    writer.write("{\"jsonrpc\":\"2.0\",\"id\":"
                            + request.path("id") + ",\"result\":" + result + "}\n");
                }
                writer.flush();
            }
        }
    }
}

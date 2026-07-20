package io.klaude.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class McpLines {
    // 禁止实例化 MCP line codec
    private McpLines() {
    }

    // 读取一条有界 UTF-8 行并分类 EOF 与超限
    static String read(InputStream input, int maximumBytes) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new McpServerUnavailableException("MCP server closed connection");
            }
            if (value == '\n') {
                if (bytes.size() == 0) {
                    continue;
                }
                return bytes.toString(StandardCharsets.UTF_8).strip();
            }
            if (value != '\r') {
                if (bytes.size() >= maximumBytes) {
                    throw new McpServerUnavailableException(
                            "MCP response too large (>" + maximumBytes + " bytes)");
                }
                bytes.write(value);
            }
        }
    }
}

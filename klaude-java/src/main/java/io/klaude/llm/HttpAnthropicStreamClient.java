package io.klaude.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpAnthropicStreamClient implements AnthropicStreamClient, AutoCloseable {
    private static final int CONTEXT_WINDOW = 200_000;
    private final URI endpoint;
    private final String apiKey;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;

    // 初始化 Anthropic endpoint、API key 和 virtual-thread HTTP client
    public HttpAnthropicStreamClient(URI endpoint, String apiKey) {
        this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = java.util.Objects.requireNonNull(apiKey, "apiKey");
        this.http = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // 发送 Anthropic streaming request 并异步解析 SSE response
    @Override
    public CompletionStage<AnthropicStreamResult> stream(
            AnthropicStreamRequest request, LlmTokenSink tokens) {
        String body;
        try {
            body = ProtocolJson.mapper().writeValueAsString(payload(request));
        } catch (IOException error) {
            return CompletableFuture.failedFuture(error);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(messagesEndpoint(endpoint))
                .timeout(Duration.ofMinutes(5))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                .handle((response, error) -> {
                    if (error != null) {
                        throw new CompletionException(new AnthropicTransportException(
                                "Anthropic connection failed", error));
                    }
                    return response;
                })
                .thenCompose(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        try (InputStream stream = response.body()) {
                            String errorBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                            return CompletableFuture.failedFuture(
                                    new AnthropicApiException(response.statusCode(), errorBody));
                        } catch (IOException error) {
                            return CompletableFuture.failedFuture(error);
                        }
                    }
                    return CompletableFuture.supplyAsync(
                            () -> parseResponse(response.body(), tokens), executor);
                });
    }

    // 在保留第三方路径前缀的前提下生成 Messages API 地址
    private static URI messagesEndpoint(URI base) {
        String value = base.toString();
        if (value.endsWith("/v1/messages")) {
            return base;
        }
        return URI.create((value.endsWith("/") ? value : value + "/") + "v1/messages");
    }

    // 构造含 prompt caching 边界的 Anthropic request JSON
    private static ObjectNode payload(AnthropicStreamRequest request) {
        var mapper = ProtocolJson.mapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", request.model());
        payload.put("max_tokens", request.maxTokens());
        payload.put("stream", true);
        ObjectNode system = mapper.createObjectNode();
        system.put("type", "text");
        system.put("text", request.request().systemPrompt());
        system.set("cache_control", mapper.createObjectNode().put("type", "ephemeral"));
        payload.set("system", mapper.createArrayNode().add(system));
        ArrayNode messages = mapper.createArrayNode();
        request.request().messages().forEach(messages::add);
        payload.set("messages", messages);
        if (!request.request().toolSchemas().isEmpty()) {
            ArrayNode tools = mapper.createArrayNode();
            List<ObjectNode> schemas = request.request().toolSchemas();
            for (int index = 0; index < schemas.size(); index++) {
                ObjectNode schema = schemas.get(index).deepCopy();
                if (index == schemas.size() - 1) {
                    schema.set("cache_control", mapper.createObjectNode().put("type", "ephemeral"));
                }
                tools.add(schema);
            }
            payload.set("tools", tools);
        }
        return payload;
    }

    // 逐个读取 SSE data 行并汇总最终结果
    private static AnthropicStreamResult parseResponse(
            InputStream input, LlmTokenSink tokens) {
        var state = new StreamState();
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String json = line.substring(5).stripLeading();
                if (json.isEmpty() || json.equals("[DONE]")) {
                    continue;
                }
                applyEvent(ProtocolJson.mapper().readTree(json), state, tokens);
            }
            return state.result();
        } catch (IOException error) {
            throw new CompletionException(new AnthropicTransportException(
                    "Anthropic stream read failed", error));
        } catch (Exception error) {
            throw new CompletionException(error);
        }
    }

    // 将一个 Anthropic SSE event 应用到流状态
    private static void applyEvent(
            JsonNode event, StreamState state, LlmTokenSink tokens) throws Exception {
        switch (event.path("type").asText()) {
            case "message_start" -> {
                JsonNode usage = event.path("message").path("usage");
                state.inputTokens = usage.path("input_tokens").asInt();
                state.cacheReadTokens = usage.path("cache_read_input_tokens").asInt();
                state.cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt();
            }
            case "content_block_start" -> {
                int index = event.path("index").asInt();
                JsonNode block = event.path("content_block");
                state.blocks.put(index, new BlockState(
                        block.path("type").asText(),
                        block.path("id").asText(),
                        block.path("name").asText(),
                        block.path("input").isObject()
                                ? ((ObjectNode) block.path("input")).deepCopy()
                                : ProtocolJson.mapper().createObjectNode()));
            }
            case "content_block_delta" -> {
                BlockState block = state.blocks.get(event.path("index").asInt());
                JsonNode delta = event.path("delta");
                switch (delta.path("type").asText()) {
                    case "text_delta" -> tokens.emit(delta.path("text").asText())
                            .toCompletableFuture().get();
                    case "thinking_delta" -> block.thinking.append(delta.path("thinking").asText());
                    case "signature_delta" -> block.signature.append(delta.path("signature").asText());
                    case "input_json_delta" -> block.inputJson.append(delta.path("partial_json").asText());
                    default -> { }
                }
            }
            case "message_delta" -> {
                state.stopReason = stopReason(event.path("delta").path("stop_reason").asText());
                state.outputTokens = event.path("usage").path("output_tokens").asInt();
            }
            default -> { }
        }
    }

    // 将 Anthropic stop reason 转为内部 enum
    private static LlmStopReason stopReason(String value) {
        return switch (value) {
            case "tool_use" -> LlmStopReason.TOOL_USE;
            case "max_tokens" -> LlmStopReason.MAX_TOKENS;
            default -> LlmStopReason.END_TURN;
        };
    }

    // 关闭 HTTP client 使用的 virtual-thread executor
    @Override
    public void close() {
        executor.close();
    }

    private static final class StreamState {
        private final Map<Integer, BlockState> blocks = new LinkedHashMap<>();
        private int inputTokens;
        private int outputTokens;
        private int cacheReadTokens;
        private int cacheCreationTokens;
        private LlmStopReason stopReason = LlmStopReason.END_TURN;

        // 将完整 SSE 状态冻结为不可变流结果
        private AnthropicStreamResult result() throws IOException {
            var thinking = new java.util.ArrayList<ObjectNode>();
            var calls = new java.util.ArrayList<LlmToolCall>();
            for (BlockState block : blocks.values()) {
                if (block.type.equals("thinking")) {
                    ObjectNode value = ProtocolJson.mapper().createObjectNode();
                    value.put("type", "thinking");
                    value.put("thinking", block.thinking.toString());
                    value.put("signature", block.signature.toString());
                    thinking.add(value);
                } else if (block.type.equals("tool_use")) {
                    ObjectNode input = block.inputJson.isEmpty()
                            ? block.initialInput
                            : (ObjectNode) ProtocolJson.mapper().readTree(block.inputJson.toString());
                    calls.add(new LlmToolCall(block.id, block.name, input));
                }
            }
            return new AnthropicStreamResult(
                    stopReason,
                    calls,
                    thinking,
                    new LlmUsage(
                            inputTokens,
                            outputTokens,
                            cacheReadTokens,
                            cacheCreationTokens,
                            (double) inputTokens / CONTEXT_WINDOW));
        }
    }

    private static final class BlockState {
        private final String type;
        private final String id;
        private final String name;
        private final ObjectNode initialInput;
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();
        private final StringBuilder inputJson = new StringBuilder();

        // 保存一个 streaming content block 的可变累积状态
        private BlockState(String type, String id, String name, ObjectNode initialInput) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.initialInput = initialInput;
        }
    }
}

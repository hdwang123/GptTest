package org.example.chatgpt.service;

import cn.hutool.core.util.StrUtil;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import org.example.chatgpt.model.ChatTurn;
import org.example.chatgpt.model.OpenAiStreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversation service based on the official OpenAI Java SDK.
 */
@Service
public class ChatServiceNew {

    private static final Logger LOG = LoggerFactory.getLogger(ChatServiceNew.class);
    private static final int MAX_HISTORY_TURNS = 10;

    private final Map<String, List<ChatTurn>> sessionHistoryMap = new ConcurrentHashMap<>();

    @Value("${openai.api-url:}")
    private String apiUrl;

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.model:gpt-4.1-mini}")
    private String model;

    @Value("${openai.max-token:2000}")
    private long maxToken;

    @Value("${openai.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${openai.proxy.port:7890}")
    private int proxyPort;

    @Async
    public void streamChatCompletion(String prompt, SseEmitter sseEmitter) {
        streamChatCompletion(null, prompt, sseEmitter);
    }

    @Async
    public void streamChatCompletion(String sessionId, String prompt, SseEmitter sseEmitter) {
        LOG.info("Send message, sessionId={}, prompt={}", sessionId, prompt);

        OpenAIClient client = null;
        StringBuilder receiveMsgBuilder = new StringBuilder();

        try {
            client = buildOpenAiClient();
            String input = buildInput(sessionId, prompt);

            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(model)
                    .maxOutputTokens(maxToken)
                    .input(input)
                    .build();

            try (StreamResponse<ResponseStreamEvent> streamResponse = client.responses().createStreaming(params)) {
                streamResponse.stream()
                        .forEach(event -> handleStreamEvent(event, sseEmitter, receiveMsgBuilder));
            }

            saveHistory(sessionId, prompt, receiveMsgBuilder.toString());
            sendStopEvent(sseEmitter);
            sseEmitter.complete();
            LOG.info("Stream completed, full message={}", receiveMsgBuilder);
        } catch (Exception e) {
            LOG.error("Stream failed", e);
            sendStopEventQuietly(sseEmitter);
            sseEmitter.completeWithError(e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    public void clearSession(String sessionId) {
        if (StrUtil.isNotBlank(sessionId)) {
            sessionHistoryMap.remove(sessionId);
        }
    }

    private void handleStreamEvent(ResponseStreamEvent event, SseEmitter sseEmitter,
                                   StringBuilder receiveMsgBuilder) {
        if (event.isOutputTextDelta()) {
            String content = event.asOutputTextDelta().delta();
            content = content == null ? StrUtil.EMPTY : content;
            receiveMsgBuilder.append(content);
            sendMessage(sseEmitter, content);
            return;
        }

        if (event.isError()) {
            throw new OpenAiStreamException(event.asError().toString());
        }
    }

    private void sendMessage(SseEmitter sseEmitter, String content) {
        try {
            sseEmitter.send(Collections.singletonMap("content", content));
        } catch (IOException e) {
            throw new OpenAiStreamException("Send SSE message failed", e);
        }
    }

    private static void sendStopEvent(SseEmitter sseEmitter) throws IOException {
        sseEmitter.send(SseEmitter.event().name("stop").data(""));
    }

    private static void sendStopEventQuietly(SseEmitter sseEmitter) {
        try {
            sendStopEvent(sseEmitter);
        } catch (IOException ignored) {
            // The client may have already closed the SSE connection.
        }
    }

    private OpenAIClient buildOpenAiClient() {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .fromEnv()
                .timeout(Duration.ofSeconds(60));

        if (StrUtil.isNotBlank(apiUrl)) {
            builder.baseUrl(apiUrl);
        }

        if (StrUtil.isNotBlank(apiKey)) {
            builder.apiKey(apiKey);
        }

        if (StrUtil.isNotBlank(proxyHost)) {
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        }

        return builder.build();
    }

    private String buildInput(String sessionId, String prompt) {
        if (StrUtil.isBlank(sessionId)) {
            return prompt;
        }

        List<ChatTurn> history = sessionHistoryMap.get(sessionId);
        if (history == null || history.isEmpty()) {
            return prompt;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Conversation history. Answer the last user message using the context below.\n\n");
        synchronized (history) {
            for (ChatTurn turn : history) {
                builder.append("User: ").append(turn.getUserMsg()).append('\n');
                builder.append("Assistant: ").append(turn.getAssistantMsg()).append("\n\n");
            }
        }
        builder.append("User: ").append(prompt).append('\n');
        builder.append("Assistant: ");
        return builder.toString();
    }

    private void saveHistory(String sessionId, String userMsg, String assistantMsg) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }

        List<ChatTurn> history = sessionHistoryMap.computeIfAbsent(
                sessionId, key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (history) {
            history.add(new ChatTurn(userMsg, assistantMsg));
            while (history.size() > MAX_HISTORY_TURNS) {
                history.remove(0);
            }
        }
    }
}

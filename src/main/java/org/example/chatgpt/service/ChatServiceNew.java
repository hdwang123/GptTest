package org.example.chatgpt.service;

import cn.hutool.core.util.StrUtil;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
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
import java.util.Collections;

/**
 * Conversation service based on the official OpenAI Java SDK.
 */
@Service
public class ChatServiceNew {

    private static final Logger LOG = LoggerFactory.getLogger(ChatServiceNew.class);

    @Value("${openai.api-url}")
    private String apiUrl;

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.model:gpt-4.1-mini}")
    private String model;

    @Value("${openai.max-token: 2000}")
    private long maxToken;

    @Value("${openai.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${openai.proxy.port:7890}")
    private int proxyPort;

    /**
     * Stream a model response to the browser through SSE.
     *
     * @param prompt     user input
     * @param sseEmitter SSE connection
     */
    @Async
    public void streamChatCompletion(String prompt, SseEmitter sseEmitter) {
        LOG.info("发送消息：{}", prompt);

        OpenAIClient client = null;
        StringBuilder receiveMsgBuilder = new StringBuilder();

        try {
            client = buildOpenAiClient();

            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(model)
                    .maxOutputTokens(maxToken)
                    .input(prompt)
                    .build();

            try (StreamResponse<ResponseStreamEvent> streamResponse = client.responses().createStreaming(params)) {
                streamResponse.stream().forEach(event -> handleStreamEvent(event, sseEmitter, receiveMsgBuilder));
            }

            LOG.info("连接结束");
            sendStopEvent(sseEmitter);
            sseEmitter.complete();
            LOG.info("收到的完整消息：{}", receiveMsgBuilder);
        } catch (Exception e) {
            LOG.error("连接异常", e);
            sendStopEventQuietly(sseEmitter);
            sseEmitter.completeWithError(e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private void handleStreamEvent(ResponseStreamEvent event, SseEmitter sseEmitter, StringBuilder receiveMsgBuilder) {
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
            throw new OpenAiStreamException("发送 SSE 消息失败", e);
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
                .fromEnv() //读环境变量
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

    private static class OpenAiStreamException extends RuntimeException {
        private OpenAiStreamException(String message) {
            super(message);
        }

        private OpenAiStreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

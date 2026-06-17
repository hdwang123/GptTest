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
 * 基于 OpenAI 官方 Java SDK 的新版聊天服务。
 */
@Service
public class ChatServiceNew {

    /**
     * 日志对象。
     */
    private static final Logger LOG = LoggerFactory.getLogger(ChatServiceNew.class);

    /**
     * 每个会话最多保留的历史轮数，防止上下文无限增长。
     */
    private static final int MAX_HISTORY_TURNS = 10;

    /**
     * 服务端内存会话历史，key 为 sessionId，value 为该会话最近若干轮对话。
     */
    private final Map<String, List<ChatTurn>> sessionHistoryMap = new ConcurrentHashMap<>();

    /**
     * OpenAI 兼容接口地址。
     */
    @Value("${openai.api-url}")
    private String apiUrl;

    /**
     * OpenAI API Key。
     */
    @Value("${openai.api-key}")
    private String apiKey;

    /**
     * 调用模型名称。
     */
    @Value("${openai.model}")
    private String model;

    /**
     * 单次回复最大输出 token 数。
     */
    @Value("${openai.max-token}")
    private long maxToken;

    /**
     * HTTP 代理主机。
     */
    @Value("${openai.proxy.host}")
    private String proxyHost;

    /**
     * HTTP 代理端口。
     */
    @Value("${openai.proxy.port}")
    private int proxyPort;

    /**
     * 兼容旧调用方式的流式聊天入口，不带会话上下文。
     *
     * @param prompt     用户输入内容
     * @param sseEmitter SSE 连接对象
     */
    @Async
    public void streamChatCompletion(String prompt, SseEmitter sseEmitter) {
        streamChatCompletion(null, prompt, sseEmitter);
    }

    /**
     * 按会话进行流式聊天，模型回复会通过 SSE 实时推送给前端。
     *
     * @param sessionId  会话 ID
     * @param prompt     用户输入内容
     * @param sseEmitter SSE 连接对象
     */
    @Async
    public void streamChatCompletion(String sessionId, String prompt, SseEmitter sseEmitter) {
        LOG.info("发送消息，sessionId={}，prompt={}", sessionId, prompt);

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
            LOG.info("流式回复结束，完整消息={}", receiveMsgBuilder);
        } catch (Exception e) {
            LOG.error("流式回复异常", e);
            sendStopEventQuietly(sseEmitter);
            sseEmitter.completeWithError(e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * 清理指定会话在服务端内存中的上下文。
     *
     * @param sessionId 会话 ID
     */
    public void clearSession(String sessionId) {
        if (StrUtil.isNotBlank(sessionId)) {
            sessionHistoryMap.remove(sessionId);
        }
    }

    /**
     * 处理 OpenAI 流式事件，将文本增量转发给浏览器。
     *
     * @param event             OpenAI 流式事件
     * @param sseEmitter        SSE 连接对象
     * @param receiveMsgBuilder 本轮完整回复缓存
     */
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

    /**
     * 向前端发送一段模型回复内容。
     *
     * @param sseEmitter SSE 连接对象
     * @param content    回复增量文本
     */
    private void sendMessage(SseEmitter sseEmitter, String content) {
        try {
            sseEmitter.send(Collections.singletonMap("content", content));
        } catch (IOException e) {
            throw new OpenAiStreamException("发送 SSE 消息失败", e);
        }
    }

    /**
     * 发送自定义 stop 事件，通知前端主动关闭 EventSource。
     *
     * @param sseEmitter SSE 连接对象
     * @throws IOException SSE 发送失败时抛出
     */
    private static void sendStopEvent(SseEmitter sseEmitter) throws IOException {
        sseEmitter.send(SseEmitter.event().name("stop").data(""));
    }

    /**
     * 安静地发送 stop 事件，异常时不再向外抛出。
     *
     * @param sseEmitter SSE 连接对象
     */
    private static void sendStopEventQuietly(SseEmitter sseEmitter) {
        try {
            sendStopEvent(sseEmitter);
        } catch (IOException ignored) {
            // 客户端可能已经关闭连接，这里忽略二次关闭错误。
        }
    }

    /**
     * 根据配置构建 OpenAI 客户端。
     *
     * @return OpenAI 客户端
     */
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

    /**
     * 根据会话历史拼接模型输入，让模型能够理解上下文。
     *
     * @param sessionId 会话 ID
     * @param prompt    当前用户输入
     * @return 拼接后的模型输入
     */
    private String buildInput(String sessionId, String prompt) {
        if (StrUtil.isBlank(sessionId)) {
            return prompt;
        }

        List<ChatTurn> history = sessionHistoryMap.get(sessionId);
        if (history == null || history.isEmpty()) {
            return prompt;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("以下是当前会话的历史对话。请结合上下文回答最后一个用户问题。\n\n");
        synchronized (history) {
            for (ChatTurn turn : history) {
                builder.append("用户：").append(turn.getUserMsg()).append('\n');
                builder.append("助手：").append(turn.getAssistantMsg()).append("\n\n");
            }
        }
        builder.append("用户：").append(prompt).append('\n');
        builder.append("助手：");
        return builder.toString();
    }

    /**
     * 保存一轮对话到服务端内存历史中。
     *
     * @param sessionId    会话 ID
     * @param userMsg      用户消息内容
     * @param assistantMsg 助手回复内容
     */
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

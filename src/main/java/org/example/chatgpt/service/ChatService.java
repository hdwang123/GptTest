package org.example.chatgpt.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiApi;
import com.theokanning.openai.completion.chat.ChatCompletionChoice;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import retrofit2.Retrofit;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.theokanning.openai.service.OpenAiService.defaultClient;
import static com.theokanning.openai.service.OpenAiService.defaultObjectMapper;
import static com.theokanning.openai.service.OpenAiService.defaultRetrofit;

/**
 * 旧版聊天服务，基于已废弃的第三方 OpenAI SDK 保留兼容。
 *
 * @author wanghuidong
 */
@Service
@Deprecated
public class ChatService {

    /**
     * 日志对象。
     */
    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    /**
     * 旧版接口使用的 API Key 占位值。
     */
    String token = "sk-XXX";

    /**
     * HTTP 代理主机。
     */
    String proxyHost = "127.0.0.1";

    /**
     * HTTP 代理端口。
     */
    int proxyPort = 7890;

    /**
     * 旧版流式聊天入口，将模型返回内容通过 SSE 推送给前端。
     *
     * @param prompt     用户输入内容
     * @param sseEmitter SSE 连接对象
     */
    @Async
    public void streamChatCompletion(String prompt, SseEmitter sseEmitter) {
        LOG.info("发送消息：{}", prompt);
        final List<ChatMessage> messages = new ArrayList<>();
        final ChatMessage systemMessage = new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt);
        messages.add(systemMessage);
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                .builder()
                .model("gpt-3.5-turbo")
                .messages(messages)
                .n(1)
                .logitBias(new HashMap<>())
                .build();

        StringBuilder receiveMsgBuilder = new StringBuilder();
        OpenAiService service = buildOpenAiService(token, proxyHost, proxyPort);
        service.streamChatCompletion(chatCompletionRequest)
                .doOnComplete(() -> {
                    LOG.info("连接结束");
                    sendStopEvent(sseEmitter);
                    sseEmitter.complete();
                })
                .doOnError(throwable -> {
                    LOG.error("连接异常", throwable);
                    sendStopEvent(sseEmitter);
                    sseEmitter.completeWithError(throwable);
                })
                .blockingForEach(x -> {
                    ChatCompletionChoice choice = x.getChoices().get(0);
                    LOG.debug("收到消息：{}", choice);
                    if (StrUtil.isEmpty(choice.getFinishReason())) {
                        sseEmitter.send(choice.getMessage());
                    }
                    String content = choice.getMessage().getContent();
                    content = content == null ? StrUtil.EMPTY : content;
                    receiveMsgBuilder.append(content);
                });
        LOG.info("收到的完整消息：{}", receiveMsgBuilder);
    }

    /**
     * 发送自定义 stop 事件，通知前端主动关闭 SSE 连接。
     *
     * @param sseEmitter SSE 连接对象
     * @throws IOException SSE 发送失败时抛出
     */
    private static void sendStopEvent(SseEmitter sseEmitter) throws IOException {
        sseEmitter.send(SseEmitter.event().name("stop").data(""));
    }

    /**
     * 构建旧版 OpenAiService 客户端。
     *
     * @param token     API Key
     * @param proxyHost 代理主机
     * @param proxyPort 代理端口
     * @return 旧版 OpenAI 服务客户端
     */
    private OpenAiService buildOpenAiService(String token, String proxyHost, int proxyPort) {
        Proxy proxy = null;
        if (StrUtil.isNotBlank(proxyHost)) {
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        }

        OkHttpClient client = defaultClient(token, Duration.of(60, ChronoUnit.SECONDS))
                .newBuilder()
                .proxy(proxy)
                .build();
        ObjectMapper mapper = defaultObjectMapper();
        Retrofit retrofit = defaultRetrofit(client, mapper);
        OpenAiApi api = retrofit.create(OpenAiApi.class);
        return new OpenAiService(api, client.dispatcher().executorService());
    }
}

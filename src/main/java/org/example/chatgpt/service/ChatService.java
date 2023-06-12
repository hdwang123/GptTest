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

import static com.theokanning.openai.service.OpenAiService.*;

/**
 * 会话服务
 *
 * @author wanghuidong
 * 时间： 2023/6/9 11:58
 */
@Service
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    String token = "sk-xxx";
    String proxyHost = "127.0.0.1";
    int proxyPort = 7890;

    /**
     * 流式对话
     * 注：必须使用异步处理（否则发送消息不会及时返回前端）
     *
     * @param prompt     输入消息
     * @param sseEmitter SSE对象
     */
    @Async
    public void streamChatCompletion(String prompt, SseEmitter sseEmitter) {
        LOG.info("Creating chat completion...");
        final List<ChatMessage> messages = new ArrayList<>();
        final ChatMessage systemMessage = new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt);
        messages.add(systemMessage);
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                .builder()
                .model("gpt-3.5-turbo")
                .messages(messages)
                .n(1)
//                .maxTokens(500)
                .logitBias(new HashMap<>())
                .build();


        //流式对话（逐Token返回）
        OpenAiService service = buildOpenAiService(token, proxyHost, proxyPort);
        service.streamChatCompletion(chatCompletionRequest)
                //正常结束
                .doOnComplete(() -> {
                    LOG.info("连接结束");

                    //发送连接关闭事件，让客户端主动断开连接避免重连
                    sendStopEvent(sseEmitter);

                    //完成请求处理
                    sseEmitter.complete();
                })
                //异常结束
                .doOnError(throwable -> {
                    LOG.error("连接异常", throwable);

                    //发送连接关闭事件，让客户端主动断开连接避免重连
                    sendStopEvent(sseEmitter);

                    //完成请求处理携带异常
                    sseEmitter.completeWithError(throwable);
                })
                //收到消息后转发到浏览器
                .blockingForEach(x -> {
                    ChatCompletionChoice choice = x.getChoices().get(0);
                    LOG.debug("收到消息：" + choice);
                    if (StrUtil.isEmpty(choice.getFinishReason())) {
                        //未结束时才可以发送消息（结束后，先调用doOnComplete然后还会收到一条结束消息，因连接关闭导致发送消息失败:ResponseBodyEmitter has already completed）
                        sseEmitter.send(choice.getMessage());
                    }
                });
    }

    /**
     * 发送连接关闭事件，让客户端主动断开连接避免重连
     *
     * @param sseEmitter
     * @throws IOException
     */
    private static void sendStopEvent(SseEmitter sseEmitter) throws IOException {
        sseEmitter.send(SseEmitter.event().name("stop").data(""));
    }


    /**
     * 构建OpenAiService
     *
     * @param token     API_KEY
     * @param proxyHost 代理域名
     * @param proxyPort 代理端口号
     * @return OpenAiService
     */
    private OpenAiService buildOpenAiService(String token, String proxyHost, int proxyPort) {
        //构建HTTP代理
        Proxy proxy = null;
        if (StrUtil.isNotBlank(proxyHost)) {
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        }
        //构建HTTP客户端
        OkHttpClient client = defaultClient(token, Duration.of(60, ChronoUnit.SECONDS))
                .newBuilder()
                .proxy(proxy)
                .build();
        ObjectMapper mapper = defaultObjectMapper();
        Retrofit retrofit = defaultRetrofit(client, mapper);
        OpenAiApi api = retrofit.create(OpenAiApi.class);
        OpenAiService service = new OpenAiService(api, client.dispatcher().executorService());
        return service;
    }
}

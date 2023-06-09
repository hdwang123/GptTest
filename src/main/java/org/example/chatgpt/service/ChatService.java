package org.example.chatgpt.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiApi;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import okhttp3.OkHttpClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import retrofit2.Retrofit;

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
        System.out.println("Creating chat completion...");
        final List<ChatMessage> messages = new ArrayList<>();
        final ChatMessage systemMessage = new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt);
        messages.add(systemMessage);
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                .builder()
                .model("gpt-3.5-turbo")
                .messages(messages)
                .n(1)
                .maxTokens(500)
                .logitBias(new HashMap<>())
                .build();


        //流式对话（逐Token返回）
        OpenAiService service = buildOpenAiService(token, proxyHost, proxyPort);
        service.streamChatCompletion(chatCompletionRequest)
                //正常结束
                .doOnComplete(() -> sseEmitter.complete())
                //异常结束
                .doOnError(throwable -> sseEmitter.completeWithError(throwable))
                //发送消息到浏览器
                .blockingForEach(x -> {
                    String message = x.getChoices().get(0).getMessage().getContent();
                    System.out.println(x.getChoices().get(0));
                    //消息不为空，再发送
                    if (StrUtil.isNotEmpty(message)) {
                        sseEmitter.send(message);
                    }
                });
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

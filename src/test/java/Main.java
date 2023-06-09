import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiApi;
import com.theokanning.openai.completion.CompletionChoice;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.completion.chat.*;
import com.theokanning.openai.image.CreateImageRequest;
import com.theokanning.openai.service.OpenAiService;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static com.theokanning.openai.service.OpenAiService.*;

/**
 * @author wanghuidong
 * 时间： 2023/6/5 17:07
 */
public class Main {

    public static void main(String[] args) {
        String token = "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";
        String proxyHost = "127.0.0.1";
        int proxyPort = 7890;

        OpenAiService service = buildOpenAiService(token, proxyHost, proxyPort);
        String prompt = "";

        //文本补全
        prompt = "没有人能";
        testCompletion(service, prompt);

//        //图片生成
        prompt = "小猫和小狗打架漫画";
//        testImageGenerate(service, prompt);
//
//        //对话
        prompt = "你有自我意识吗？";
        testChatCompletion(service, prompt);

        //立即释放连接
        service.shutdownExecutor();
    }

    private static void testChatCompletion(OpenAiService service, String prompt) {
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
        //完整对话
        service.createChatCompletion(chatCompletionRequest)
                .getChoices().forEach(new Consumer<ChatCompletionChoice>() {
                    @Override
                    public void accept(ChatCompletionChoice chatCompletionChoice) {
                        System.out.println(chatCompletionChoice.getMessage());
                    }
                });

        //流式对话（逐Token返回）
//        service.streamChatCompletion(chatCompletionRequest)
//                .doOnError(Throwable::printStackTrace)
//                .blockingForEach(System.out::println);
    }

    private static void testImageGenerate(OpenAiService service, String prompt) {
        System.out.println("\nCreating Image...");
        CreateImageRequest request = CreateImageRequest.builder()
                .prompt(prompt)
                .build();

        System.out.println("\nImage is located at:");
        System.out.println(service.createImage(request).getData().get(0).getUrl());
    }

    private static void testCompletion(OpenAiService service, String prompt) {
        System.out.println("\nCreating completion...");
        CompletionRequest completionRequest = CompletionRequest.builder()
                .model("text-davinci-003")
                .prompt(prompt)
                .echo(true)
                .user("testing")
                .n(3)
                .build();
        service.createCompletion(completionRequest).getChoices().forEach(new Consumer<CompletionChoice>() {
            @Override
            public void accept(CompletionChoice completionChoice) {
                System.out.println(completionChoice.getText());
            }
        });
    }

    private static OpenAiService buildOpenAiService(String token, String proxyHost, int proxyPort) {
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

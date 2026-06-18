package org.example.chatgpt.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.ConnectionSpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;
import org.example.chatgpt.model.ImageResult;
import org.example.chatgpt.model.ImageTurn;
import org.example.chatgpt.model.OpenAiStreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片生成服务，负责调用阿里云百炼通义万相并维护图片会话上下文。
 */
@Service
public class ImageService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final int MAX_IMAGE_HISTORY_TURNS = 5;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, List<ImageTurn>> sessionImageHistoryMap = new ConcurrentHashMap<>();

    @Value("${openai.image-api-url}")
    private String imageApiUrl;

    @Value("${openai.image-api-key}")
    private String imageApiKey;

    @Value("${openai.image-model}")
    private String imageModel;

    @Value("${openai.image-task-api-url}")
    private String imageTaskApiUrl;

    @Value("${openai.image-size}")
    private String imageSize;

    @Value("${openai.image-poll-interval-ms}")
    private long imagePollIntervalMs;

    @Value("${openai.image-poll-max-attempts}")
    private int imagePollMaxAttempts;

    @Value("${openai.proxy.host}")
    private String proxyHost;

    @Value("${openai.proxy.port}")
    private int proxyPort;

    @Value("${openai.image-storage-dir}")
    private String imageStorageDir;

    @Value("${openai.image-public-url-prefix}")
    private String imagePublicUrlPrefix;

    /**
     * 判断文本是否包含常见的图片生成意图。
     *
     * @param prompt 用户输入
     * @return 是否为图片生成请求
     */
    public boolean isImageRequest(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return false;
        }

        String lowerPrompt = prompt.toLowerCase(Locale.ROOT);
        return lowerPrompt.contains("生成图片")
                || lowerPrompt.contains("画一")
                || lowerPrompt.contains("画个")
                || lowerPrompt.contains("画张")
                || lowerPrompt.contains("绘制")
                || lowerPrompt.contains("做一张")
                || lowerPrompt.contains("图片")
                || lowerPrompt.contains("海报")
                || lowerPrompt.contains("插画")
                || lowerPrompt.contains("logo")
                || lowerPrompt.contains("draw ")
                || lowerPrompt.contains("generate an image")
                || lowerPrompt.contains("create an image")
                || lowerPrompt.contains("make an image");
    }

    /**
     * 判断文本是否为已有图片会话的后续修改请求。
     *
     * @param sessionId 会话编号
     * @param prompt    用户输入
     * @return 是否为图片后续请求
     */
    public boolean isImageFollowUp(String sessionId, String prompt) {
        if (!hasImageHistory(sessionId) || StrUtil.isBlank(prompt)) {
            return false;
        }

        String lowerPrompt = prompt.toLowerCase(Locale.ROOT);
        return lowerPrompt.contains("它")
                || lowerPrompt.contains("这张")
                || lowerPrompt.contains("上一张")
                || lowerPrompt.contains("刚才")
                || lowerPrompt.contains("改")
                || lowerPrompt.contains("换")
                || lowerPrompt.contains("加")
                || lowerPrompt.contains("去掉")
                || lowerPrompt.contains("背景")
                || lowerPrompt.contains("颜色")
                || lowerPrompt.contains("风格")
                || lowerPrompt.contains("it")
                || lowerPrompt.contains("this image")
                || lowerPrompt.contains("previous image")
                || lowerPrompt.contains("change")
                || lowerPrompt.contains("make it")
                || lowerPrompt.contains("add ")
                || lowerPrompt.contains("remove ");
    }

    /**
     * 判断指定会话是否存在图片历史。
     *
     * @param sessionId 会话编号
     * @return 是否存在图片历史
     */
    private boolean hasImageHistory(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return false;
        }
        List<ImageTurn> history = sessionImageHistoryMap.get(sessionId);
        return history != null && !history.isEmpty();
    }

    /**
     * 异步生成图片，并通过 SSE 向前端发送进度和结果。
     *
     * @param sessionId  会话编号
     * @param prompt     图片提示词
     * @param sseEmitter SSE 连接对象
     */
    @Async
    public void streamImageGeneration(String sessionId, String prompt, SseEmitter sseEmitter) {
        try {
            sendMessage(sseEmitter, "正在生成图片，请稍等...\n");
            ImageResult imageResult = generateImage(sessionId, prompt);
            sendImageMessage(sseEmitter, imageResult.getPublicUrl(),
                    "图片已生成：" + imageResult.getPublicUrl());
            saveHistory(sessionId, prompt, imageResult);
            sendStopEvent(sseEmitter);
            sseEmitter.complete();
        } catch (Exception e) {
            sendStopEventQuietly(sseEmitter);
            sseEmitter.completeWithError(e);
        }
    }

    /**
     * 生成单张图片并返回本地访问地址。
     *
     * @param prompt 图片提示词
     * @return 本地图片地址
     * @throws IOException 图片生成或保存失败时抛出
     */
    public String generateImage(String prompt) throws IOException {
        return generateImage(null, prompt).getPublicUrl();
    }

    /**
     * 提交通义万相任务，等待生成完成并下载图片。
     *
     * @param sessionId 会话编号
     * @param prompt    图片提示词
     * @return 图片生成结果
     * @throws IOException 调用上游服务失败时抛出
     */
    private ImageResult generateImage(String sessionId, String prompt) throws IOException {
        OkHttpClient client = buildHttpClient();
        validateDashScopeConfig();
        String requestJson = buildDashScopeImageRequest(sessionId, prompt);

        Request request = new Request.Builder()
                .url(buildImageGenerationUrl())
                .header("Authorization", "Bearer " + imageApiKey)
                .header("X-DashScope-Async", "enable")
                .header("Content-Type", "application/json")
                .header("User-Agent", "GptTest/1.0")
                .post(RequestBody.create(requestJson, JSON_MEDIA_TYPE))
                .build();

        try (Response response = executeWithSslRetry(client, request)) {
            ResponseBody body = response.body();
            String responseJson = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("图片生成任务创建失败：HTTP " + response.code()
                        + "，model=" + imageModel + "，response=" + responseJson);
            }

            String taskId = objectMapper.readTree(responseJson).path("output").path("task_id").asText();
            if (StrUtil.isBlank(taskId)) {
                throw new IOException("图片生成响应中缺少 task_id。");
            }

            String sourceImageUrl = waitForImage(client, taskId);
            String publicUrl = downloadImage(client, sourceImageUrl);
            return new ImageResult(publicUrl, sourceImageUrl);
        }
    }

    /**
     * 创建图片服务使用的 HTTP 客户端。
     *
     * @return HTTP 客户端
     */
    private OkHttpClient buildHttpClient() {
        ConnectionSpec tls12Spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build();

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofMinutes(3))
                .writeTimeout(Duration.ofSeconds(60))
                .retryOnConnectionFailure(true)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectionSpecs(Arrays.asList(tls12Spec, ConnectionSpec.CLEARTEXT));

        if (StrUtil.isNotBlank(proxyHost)) {
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        }
        return builder.build();
    }

    /**
     * 执行 HTTP 请求，并在 TLS 握手异常时重试一次。
     *
     * @param client  HTTP 客户端
     * @param request HTTP 请求
     * @return HTTP 响应
     * @throws IOException 请求失败时抛出
     */
    private Response executeWithSslRetry(OkHttpClient client, Request request) throws IOException {
        try {
            return client.newCall(request).execute();
        } catch (SSLHandshakeException e) {
            return client.newCall(request).execute();
        }
    }

    /**
     * 向前端发送文本消息。
     *
     * @param sseEmitter SSE 连接对象
     * @param content    消息内容
     */
    private void sendMessage(SseEmitter sseEmitter, String content) {
        try {
            sseEmitter.send(Collections.singletonMap("content", content));
        } catch (IOException e) {
            throw new OpenAiStreamException("发送 SSE 消息失败", e);
        }
    }

    /**
     * 向前端发送图片消息。
     *
     * @param sseEmitter SSE 连接对象
     * @param imageUrl   图片地址
     * @param content    消息内容
     */
    private void sendImageMessage(SseEmitter sseEmitter, String imageUrl, String content) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("type", "image");
            payload.put("content", content);
            payload.put("imageUrl", imageUrl);
            sseEmitter.send(payload);
        } catch (IOException e) {
            throw new OpenAiStreamException("发送 SSE 图片消息失败", e);
        }
    }

    /**
     * 发送停止事件，通知前端关闭 SSE 连接。
     *
     * @param sseEmitter SSE 连接对象
     * @throws IOException 发送失败时抛出
     */
    private static void sendStopEvent(SseEmitter sseEmitter) throws IOException {
        sseEmitter.send(SseEmitter.event().name("stop").data(""));
    }

    /**
     * 安静地发送停止事件，忽略客户端已断开造成的异常。
     *
     * @param sseEmitter SSE 连接对象
     */
    private static void sendStopEventQuietly(SseEmitter sseEmitter) {
        try {
            sendStopEvent(sseEmitter);
        } catch (IOException ignored) {
            // 客户端可能已经关闭连接。
        }
    }

    /**
     * 获取图片任务创建接口地址。
     *
     * @return 图片任务创建接口地址
     */
    private String buildImageGenerationUrl() {
        return imageApiUrl;
    }

    /**
     * 构造通义万相图片生成请求体。
     *
     * @param sessionId 会话编号
     * @param prompt    当前图片提示词
     * @return JSON 请求体
     */
    private String buildDashScopeImageRequest(String sessionId, String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", imageModel);

        ObjectNode input = root.putObject("input");
        input.put("prompt", buildImagePrompt(sessionId, prompt));

        ObjectNode parameters = root.putObject("parameters");
        parameters.put("size", imageSize);
        parameters.put("n", 1);
        return root.toString();
    }

    /**
     * 拼接上一轮图片提示词和当前提示词。
     *
     * @param sessionId 会话编号
     * @param prompt    当前图片提示词
     * @return 完整图片提示词
     */
    private String buildImagePrompt(String sessionId, String prompt) {
        if (StrUtil.isBlank(sessionId)) {
            return prompt;
        }

        List<ImageTurn> history = sessionImageHistoryMap.get(sessionId);
        if (history == null || history.isEmpty()) {
            return prompt;
        }

        synchronized (history) {
            return history.get(history.size() - 1).getPrompt() + "\n" + prompt;
        }
    }

    /**
     * 校验阿里云百炼 API Key 是否已配置。
     *
     * @throws IOException 未配置 API Key 时抛出
     */
    private void validateDashScopeConfig() throws IOException {
        if (StrUtil.isBlank(imageApiKey)) {
            throw new IOException("未配置 DASHSCOPE_API_KEY。");
        }
    }

    /**
     * 轮询图片生成任务，直到成功、失败或超时。
     *
     * @param client HTTP 客户端
     * @param taskId 图片任务编号
     * @return 上游图片地址
     * @throws IOException 查询失败或任务失败时抛出
     */
    private String waitForImage(OkHttpClient client, String taskId) throws IOException {
        String taskUrl = StrUtil.removeSuffix(imageTaskApiUrl, "/") + "/" + taskId;

        for (int attempt = 0; attempt < imagePollMaxAttempts; attempt++) {
            Request request = new Request.Builder()
                    .url(taskUrl)
                    .header("Authorization", "Bearer " + imageApiKey)
                    .header("User-Agent", "GptTest/1.0")
                    .get()
                    .build();

            try (Response response = executeWithSslRetry(client, request)) {
                ResponseBody body = response.body();
                String responseJson = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new IOException("图片任务查询失败：HTTP " + response.code()
                            + "，response=" + responseJson);
                }

                JsonNode output = objectMapper.readTree(responseJson).path("output");
                String status = output.path("task_status").asText();
                if ("SUCCEEDED".equals(status)) {
                    String imageUrl = output.path("results").path(0).path("url").asText();
                    if (StrUtil.isBlank(imageUrl)) {
                        throw new IOException("图片任务成功，但响应中缺少图片地址。");
                    }
                    return imageUrl;
                }
                if ("FAILED".equals(status) || "CANCELED".equals(status)
                        || "UNKNOWN".equals(status)) {
                    throw new IOException("图片任务失败：status=" + status
                            + "，response=" + responseJson);
                }
            }
            sleepBeforeNextPoll();
        }
        throw new IOException("图片生成任务超时：taskId=" + taskId);
    }

    /**
     * 等待下一次图片任务轮询。
     *
     * @throws IOException 等待线程被中断时抛出
     */
    private void sleepBeforeNextPoll() throws IOException {
        try {
            Thread.sleep(imagePollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("图片任务轮询被中断。", e);
        }
    }

    /**
     * 下载上游图片并保存到本地目录。
     *
     * @param client         HTTP 客户端
     * @param sourceImageUrl 上游图片地址
     * @return 本地图片访问地址
     * @throws IOException 下载或保存失败时抛出
     */
    private String downloadImage(OkHttpClient client, String sourceImageUrl) throws IOException {
        Request request = new Request.Builder()
                .url(sourceImageUrl)
                .header("User-Agent", "GptTest/1.0")
                .get()
                .build();

        try (Response response = executeWithSslRetry(client, request)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("图片下载失败：HTTP " + response.code());
            }
            return saveImageBytes(body.bytes(), "png");
        }
    }

    /**
     * 保存一轮图片生成记录，并限制每个会话的历史轮数。
     *
     * @param sessionId  会话编号
     * @param prompt     图片提示词
     * @param imageResult 图片生成结果
     */
    private void saveHistory(String sessionId, String prompt, ImageResult imageResult) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }

        List<ImageTurn> history = sessionImageHistoryMap.computeIfAbsent(
                sessionId, key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (history) {
            history.add(new ImageTurn(prompt, imageResult.getPublicUrl(), imageResult.getSourceImageUrl()));
            while (history.size() > MAX_IMAGE_HISTORY_TURNS) {
                history.remove(0);
            }
        }
    }

    /**
     * 将图片字节保存到本地存储目录。
     *
     * @param imageBytes 图片字节
     * @param extension  文件扩展名
     * @return 本地图片访问地址
     * @throws IOException 保存失败时抛出
     */
    private String saveImageBytes(byte[] imageBytes, String extension) throws IOException {
        Path storagePath = Paths.get(imageStorageDir).toAbsolutePath().normalize();
        Files.createDirectories(storagePath);

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path imagePath = storagePath.resolve(fileName);
        Files.write(imagePath, imageBytes);
        return StrUtil.removeSuffix(imagePublicUrlPrefix, "/") + "/" + fileName;
    }
}

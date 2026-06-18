package org.example.chatgpt.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private boolean hasImageHistory(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return false;
        }

        List<ImageTurn> history = sessionImageHistoryMap.get(sessionId);
        return history != null && !history.isEmpty();
    }

    @Async
    public void streamImageGeneration(String sessionId, String prompt, SseEmitter sseEmitter) {
        try {
            sendMessage(sseEmitter, "正在生成图片，请稍等...\n");
            ImageResult imageResult = generateImage(sessionId, prompt);
            sendImageMessage(sseEmitter, imageResult.getPublicUrl(), "图片已生成：" + imageResult.getPublicUrl());
            saveHistory(sessionId, prompt, imageResult);
            sendStopEvent(sseEmitter);
            sseEmitter.complete();
        } catch (Exception e) {
            sendStopEventQuietly(sseEmitter);
            sseEmitter.completeWithError(e);
        }
    }

    public String generateImage(String prompt) throws IOException {
        return generateImage(null, prompt).getPublicUrl();
    }

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
                throw new IOException("Image generation failed: HTTP " + response.code()
                        + ", url=" + buildImageGenerationUrl()
                        + ", model=" + imageModel
                        + ", response=" + responseJson);
            }

            String taskId = objectMapper.readTree(responseJson).path("output").path("task_id").asText();
            if (StrUtil.isNotBlank(taskId)) {
                String sourceImageUrl = waitForImage(client, taskId);
                String publicUrl = downloadImage(client, sourceImageUrl);
                return new ImageResult(publicUrl, sourceImageUrl);
            }

            throw new IOException("Image generation response does not contain task_id.");
        }
    }

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

    private Response executeWithSslRetry(OkHttpClient client, Request request) throws IOException {
        try {
            return client.newCall(request).execute();
        } catch (SSLHandshakeException e) {
            return client.newCall(request).execute();
        }
    }

    private void sendMessage(SseEmitter sseEmitter, String content) {
        try {
            sseEmitter.send(Collections.singletonMap("content", content));
        } catch (IOException e) {
            throw new OpenAiStreamException("发送 SSE 消息失败", e);
        }
    }

    private void sendImageMessage(SseEmitter sseEmitter, String imageUrl, String content) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("type", "image");
            payload.put("content", content);
            payload.put("imageUrl", imageUrl);
            sseEmitter.send(payload);
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
            // Client may already have closed the SSE connection.
        }
    }

    private String buildImageGenerationUrl() {
        return imageApiUrl;
    }

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

    private void appendHistoryMessages(ArrayNode messages, String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }

        List<ImageTurn> history = sessionImageHistoryMap.get(sessionId);
        if (history == null || history.isEmpty()) {
            return;
        }

        synchronized (history) {
            for (ImageTurn turn : history) {
                ObjectNode userMessage = messages.addObject();
                userMessage.put("role", "user");
                userMessage.put("content", turn.getPrompt());

                ObjectNode assistantMessage = messages.addObject();
                assistantMessage.put("role", "assistant");
                assistantMessage.put("content", "已生成图片：" + turn.getPublicUrl());
            }
        }
    }

    private void appendCurrentImageMessage(ArrayNode messages, String sessionId, String prompt) {
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");

        String lastSourceImageUrl = getLastSourceImageUrl(sessionId);
        if (StrUtil.isBlank(lastSourceImageUrl)) {
            userMessage.put("content", prompt);
            return;
        }

        ArrayNode content = userMessage.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        ObjectNode imageUrl = imagePart.putObject("image_url");
        imageUrl.put("url", lastSourceImageUrl);
    }

    private String getLastSourceImageUrl(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return StrUtil.EMPTY;
        }

        List<ImageTurn> history = sessionImageHistoryMap.get(sessionId);
        if (history == null || history.isEmpty()) {
            return StrUtil.EMPTY;
        }

        synchronized (history) {
            return history.get(history.size() - 1).getSourceImageUrl();
        }
    }

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

    private void validateDashScopeConfig() throws IOException {
        if (StrUtil.isBlank(imageApiKey)) {
            throw new IOException("DASHSCOPE_API_KEY is not configured.");
        }
    }

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
                    throw new IOException("Image task query failed: HTTP " + response.code()
                            + ", response=" + responseJson);
                }

                JsonNode output = objectMapper.readTree(responseJson).path("output");
                String status = output.path("task_status").asText();
                if ("SUCCEEDED".equals(status)) {
                    String imageUrl = output.path("results").path(0).path("url").asText();
                    if (StrUtil.isBlank(imageUrl)) {
                        throw new IOException("Image task succeeded without an image URL.");
                    }
                    return imageUrl;
                }
                if ("FAILED".equals(status) || "CANCELED".equals(status)
                        || "UNKNOWN".equals(status)) {
                    throw new IOException("Image task failed: status=" + status
                            + ", response=" + responseJson);
                }
            }

            sleepBeforeNextPoll();
        }

        throw new IOException("Image generation timed out: taskId=" + taskId);
    }

    private void sleepBeforeNextPoll() throws IOException {
        try {
            Thread.sleep(imagePollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Image task polling was interrupted.", e);
        }
    }

    private String downloadImage(OkHttpClient client, String sourceImageUrl) throws IOException {
        Request request = new Request.Builder()
                .url(sourceImageUrl)
                .header("User-Agent", "GptTest/1.0")
                .get()
                .build();

        try (Response response = executeWithSslRetry(client, request)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("Image download failed: HTTP " + response.code());
            }
            return saveImageBytes(body.bytes(), "png");
        }
    }

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

    private String findImageUrl(JsonNode root) {
        JsonNode messageNode = root.path("choices").path(0).path("message");
        JsonNode imagesNode = messageNode.path("images");
        if (imagesNode.isArray() && imagesNode.size() > 0) {
            JsonNode firstImageNode = imagesNode.path(0);
            String nestedUrl = firstImageNode.path("image_url").path("url").asText();
            if (StrUtil.isNotBlank(nestedUrl)) {
                return nestedUrl;
            }

            String directUrl = firstImageNode.path("url").asText();
            if (StrUtil.isNotBlank(directUrl)) {
                return directUrl;
            }
        }

        return messageNode.path("image_url").path("url").asText();
    }

    private String saveDataUrlImage(String dataUrl) throws IOException {
        int commaIndex = dataUrl.indexOf(',');
        if (commaIndex < 0) {
            throw new IOException("Image data URL does not contain base64 payload.");
        }

        String metadata = dataUrl.substring(0, commaIndex);
        String extension = resolveImageExtension(metadata);
        String b64Json = dataUrl.substring(commaIndex + 1);
        return saveBase64Image(b64Json, extension);
    }

    private String resolveImageExtension(String metadata) {
        if (metadata.contains("image/jpeg") || metadata.contains("image/jpg")) {
            return "jpg";
        }
        if (metadata.contains("image/webp")) {
            return "webp";
        }
        return "png";
    }

    private String saveBase64Image(String b64Json, String extension) throws IOException {
        return saveImageBytes(Base64.getDecoder().decode(b64Json), extension);
    }

    private String saveImageBytes(byte[] imageBytes, String extension) throws IOException {
        Path storagePath = Paths.get(imageStorageDir).toAbsolutePath().normalize();
        Files.createDirectories(storagePath);

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path imagePath = storagePath.resolve(fileName);
        Files.write(imagePath, imageBytes);

        return StrUtil.removeSuffix(imagePublicUrlPrefix, "/") + "/" + fileName;
    }

    private static class ImageResult {

        private final String publicUrl;

        private final String sourceImageUrl;

        private ImageResult(String publicUrl, String sourceImageUrl) {
            this.publicUrl = publicUrl;
            this.sourceImageUrl = sourceImageUrl;
        }

        private String getPublicUrl() {
            return publicUrl;
        }

        private String getSourceImageUrl() {
            return sourceImageUrl;
        }
    }

    private static class ImageTurn {

        private final String prompt;

        private final String publicUrl;

        private final String sourceImageUrl;

        private ImageTurn(String prompt, String publicUrl, String sourceImageUrl) {
            this.prompt = prompt;
            this.publicUrl = publicUrl;
            this.sourceImageUrl = sourceImageUrl;
        }

        private String getPrompt() {
            return prompt;
        }

        private String getPublicUrl() {
            return publicUrl;
        }

        private String getSourceImageUrl() {
            return sourceImageUrl;
        }
    }
}

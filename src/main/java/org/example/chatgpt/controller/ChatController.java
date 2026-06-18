package org.example.chatgpt.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import org.example.chatgpt.model.PendingMessage;
import org.example.chatgpt.service.ChatService;
import org.example.chatgpt.service.ImageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话控制器，负责接收消息、建立 SSE 连接并提供本地图片访问接口。
 */
@Controller
@RequestMapping("/chat")
public class ChatController {

    private final Map<String, PendingMessage> msgMap = new ConcurrentHashMap<>();

    @Autowired
    private ChatService chatService;

    @Autowired
    private ImageService imageService;

    @Value("${openai.image-storage-dir}")
    private String imageStorageDir;

    /**
     * 创建新的会话编号。
     *
     * @return 新会话编号
     */
    @ResponseBody
    @PostMapping("/newSession")
    public String newSession() {
        return IdUtil.simpleUUID();
    }

    /**
     * 暂存用户消息并返回消息编号，供后续 SSE 请求消费。
     *
     * @param sessionId 当前会话编号
     * @param msg       用户输入内容
     * @param mode      处理模式，支持 chat 和 image
     * @return 消息编号
     */
    @ResponseBody
    @PostMapping("/sendMsg")
    public String sendMsg(String sessionId, String msg, String mode) {
        String msgId = IdUtil.simpleUUID();
        String currentSessionId = StrUtil.isBlank(sessionId) ? IdUtil.simpleUUID() : sessionId;
        msgMap.put(msgId, new PendingMessage(currentSessionId, msg, mode));
        return msgId;
    }

    /**
     * 建立 SSE 连接，并根据消息模式调用聊天服务或图片服务。
     *
     * @param msgId 消息编号
     * @return SSE 连接对象
     */
    @GetMapping(value = "/conversation/{msgId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter conversation(@PathVariable("msgId") String msgId) {
        SseEmitter sseEmitter = new SseEmitter();
        PendingMessage pendingMessage = msgMap.remove(msgId);

        if (pendingMessage == null || StrUtil.isBlank(pendingMessage.getMsg())) {
            sseEmitter.complete();
            return sseEmitter;
        }

        boolean imageMode = "image".equalsIgnoreCase(pendingMessage.getMode());
        boolean legacyAutoImageMode = StrUtil.isBlank(pendingMessage.getMode())
                && (imageService.isImageRequest(pendingMessage.getMsg())
                || imageService.isImageFollowUp(pendingMessage.getSessionId(), pendingMessage.getMsg()));

        if (imageMode || legacyAutoImageMode) {
            imageService.streamImageGeneration(pendingMessage.getSessionId(), pendingMessage.getMsg(), sseEmitter);
        } else {
            chatService.streamChatCompletion(pendingMessage.getSessionId(), pendingMessage.getMsg(), sseEmitter);
        }
        return sseEmitter;
    }

    /**
     * 读取本地生成的图片，并阻止访问图片目录之外的文件。
     *
     * @param fileName 图片文件名
     * @return 图片资源响应
     */
    @GetMapping("/image/{fileName:.+}")
    public ResponseEntity<Resource> image(@PathVariable("fileName") String fileName) {
        try {
            Path storagePath = Paths.get(imageStorageDir).toAbsolutePath().normalize();
            Path imagePath = storagePath.resolve(fileName).normalize();
            if (!imagePath.startsWith(storagePath) || !Files.isRegularFile(imagePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            String contentType = Files.probeContentType(imagePath);
            MediaType mediaType = StrUtil.isBlank(contentType)
                    ? MediaType.IMAGE_PNG
                    : MediaType.parseMediaType(contentType);

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(new FileSystemResource(imagePath.toFile()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}

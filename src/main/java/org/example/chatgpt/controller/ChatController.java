package org.example.chatgpt.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import org.example.chatgpt.model.PendingMessage;
import org.example.chatgpt.service.ChatServiceNew;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天控制器，负责接收前端消息、创建会话和建立 SSE 流式连接。
 */
@Controller
@RequestMapping("/chat")
public class ChatController {

    /**
     * 临时保存待消费消息。EventSource 只能发起 GET 请求，所以先用 POST 保存消息，再用消息 ID 建立 SSE 连接。
     */
    private final Map<String, PendingMessage> msgMap = new ConcurrentHashMap<>();

    /**
     * 新版聊天服务。
     */
    @Autowired
    private ChatServiceNew chatService;

    /**
     * 创建一个新的会话 ID。
     *
     * @return 新会话 ID
     */
    @ResponseBody
    @PostMapping("/newSession")
    public String newSession() {
        return IdUtil.simpleUUID();
    }

    /**
     * 接收用户消息并生成消息 ID，供后续 SSE 请求使用。
     *
     * @param sessionId 当前会话 ID
     * @param msg       用户输入内容
     * @return 消息 ID
     */
    @ResponseBody
    @PostMapping("/sendMsg")
    public String sendMsg(String sessionId, String msg) {
        String msgId = IdUtil.simpleUUID();
        String currentSessionId = StrUtil.isBlank(sessionId) ? IdUtil.simpleUUID() : sessionId;
        msgMap.put(msgId, new PendingMessage(currentSessionId, msg));
        return msgId;
    }

    /**
     * 根据消息 ID 建立 SSE 连接，并把模型输出流式推送给浏览器。
     *
     * @param msgId 消息 ID
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

        chatService.streamChatCompletion(pendingMessage.getSessionId(), pendingMessage.getMsg(), sseEmitter);
        return sseEmitter;
    }
}

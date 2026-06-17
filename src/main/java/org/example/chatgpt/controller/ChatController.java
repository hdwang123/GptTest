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
 * Chat controller.
 */
@Controller
@RequestMapping("/chat")
public class ChatController {

    private final Map<String, PendingMessage> msgMap = new ConcurrentHashMap<>();

    @Autowired
    private ChatServiceNew chatService;

    @ResponseBody
    @PostMapping("/newSession")
    public String newSession() {
        return IdUtil.simpleUUID();
    }

    @ResponseBody
    @PostMapping("/sendMsg")
    public String sendMsg(String sessionId, String msg) {
        String msgId = IdUtil.simpleUUID();
        String currentSessionId = StrUtil.isBlank(sessionId) ? IdUtil.simpleUUID() : sessionId;
        msgMap.put(msgId, new PendingMessage(currentSessionId, msg));
        return msgId;
    }

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

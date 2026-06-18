package org.example.chatgpt.model;

/**
 * 单轮聊天记录，用于维护聊天会话上下文。
 */
public class ChatTurn {

    private final String userMsg;

    private final String assistantMsg;

    /**
     * 创建一轮聊天记录。
     *
     * @param userMsg      用户消息
     * @param assistantMsg 助手回复
     */
    public ChatTurn(String userMsg, String assistantMsg) {
        this.userMsg = userMsg;
        this.assistantMsg = assistantMsg;
    }

    /**
     * 获取用户消息。
     *
     * @return 用户消息
     */
    public String getUserMsg() {
        return userMsg;
    }

    /**
     * 获取助手回复。
     *
     * @return 助手回复
     */
    public String getAssistantMsg() {
        return assistantMsg;
    }
}

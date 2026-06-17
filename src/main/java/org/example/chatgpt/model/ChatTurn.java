package org.example.chatgpt.model;

/**
 * 单轮对话记录，用于在服务端内存中保存会话上下文。
 */
public class ChatTurn {

    /**
     * 用户消息内容。
     */
    private final String userMsg;

    /**
     * 助手回复内容。
     */
    private final String assistantMsg;

    /**
     * 创建一轮对话记录。
     *
     * @param userMsg      用户消息内容
     * @param assistantMsg 助手回复内容
     */
    public ChatTurn(String userMsg, String assistantMsg) {
        this.userMsg = userMsg;
        this.assistantMsg = assistantMsg;
    }

    /**
     * 获取用户消息内容。
     *
     * @return 用户消息内容
     */
    public String getUserMsg() {
        return userMsg;
    }

    /**
     * 获取助手回复内容。
     *
     * @return 助手回复内容
     */
    public String getAssistantMsg() {
        return assistantMsg;
    }
}

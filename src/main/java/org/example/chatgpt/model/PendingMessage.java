package org.example.chatgpt.model;

/**
 * 等待 SSE 连接消费的用户消息。
 */
public class PendingMessage {

    private final String sessionId;

    private final String msg;

    private final String mode;

    /**
     * 创建待处理消息。
     *
     * @param sessionId 会话编号
     * @param msg       用户消息
     * @param mode      处理模式
     */
    public PendingMessage(String sessionId, String msg, String mode) {
        this.sessionId = sessionId;
        this.msg = msg;
        this.mode = mode;
    }

    /**
     * 获取会话编号。
     *
     * @return 会话编号
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取用户消息。
     *
     * @return 用户消息
     */
    public String getMsg() {
        return msg;
    }

    /**
     * 获取处理模式。
     *
     * @return 处理模式
     */
    public String getMode() {
        return mode;
    }
}

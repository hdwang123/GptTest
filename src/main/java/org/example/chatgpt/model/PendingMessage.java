package org.example.chatgpt.model;

/**
 * 等待建立 SSE 连接消费的用户消息。
 */
public class PendingMessage {

    /**
     * 消息所属会话 ID。
     */
    private final String sessionId;

    /**
     * 用户消息内容。
     */
    private final String msg;

    /**
     * 创建待处理消息。
     *
     * @param sessionId 消息所属会话 ID
     * @param msg       用户消息内容
     */
    public PendingMessage(String sessionId, String msg) {
        this.sessionId = sessionId;
        this.msg = msg;
    }

    /**
     * 获取消息所属会话 ID。
     *
     * @return 会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取用户消息内容。
     *
     * @return 用户消息内容
     */
    public String getMsg() {
        return msg;
    }
}

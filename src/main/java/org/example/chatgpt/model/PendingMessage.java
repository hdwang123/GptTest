package org.example.chatgpt.model;

public class PendingMessage {

    private final String sessionId;
    private final String msg;

    public PendingMessage(String sessionId, String msg) {
        this.sessionId = sessionId;
        this.msg = msg;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getMsg() {
        return msg;
    }
}

package org.example.chatgpt.model;

public class ChatTurn {

    private final String userMsg;
    private final String assistantMsg;

    public ChatTurn(String userMsg, String assistantMsg) {
        this.userMsg = userMsg;
        this.assistantMsg = assistantMsg;
    }

    public String getUserMsg() {
        return userMsg;
    }

    public String getAssistantMsg() {
        return assistantMsg;
    }
}

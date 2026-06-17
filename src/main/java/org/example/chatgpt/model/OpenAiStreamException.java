package org.example.chatgpt.model;

public class OpenAiStreamException extends RuntimeException {

    public OpenAiStreamException(String message) {
        super(message);
    }

    public OpenAiStreamException(String message, Throwable cause) {
        super(message, cause);
    }
}

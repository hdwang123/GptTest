package org.example.chatgpt.model;

/**
 * 模型流式调用过程中发生的业务异常。
 */
public class OpenAiStreamException extends RuntimeException {

    /**
     * 使用错误消息创建异常。
     *
     * @param message 错误消息
     */
    public OpenAiStreamException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和原始异常创建异常。
     *
     * @param message 错误消息
     * @param cause   原始异常
     */
    public OpenAiStreamException(String message, Throwable cause) {
        super(message, cause);
    }
}

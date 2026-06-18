package org.example.chatgpt.model;

/**
 * 单轮图片生成记录，用于维护图片会话上下文。
 */
public class ImageTurn {

    private final String prompt;

    private final String publicUrl;

    private final String sourceImageUrl;

    /**
     * 创建一轮图片生成记录。
     *
     * @param prompt         图片提示词
     * @param publicUrl      前端可访问的本地图片地址
     * @param sourceImageUrl 上游服务返回的图片地址
     */
    public ImageTurn(String prompt, String publicUrl, String sourceImageUrl) {
        this.prompt = prompt;
        this.publicUrl = publicUrl;
        this.sourceImageUrl = sourceImageUrl;
    }

    /**
     * 获取图片提示词。
     *
     * @return 图片提示词
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * 获取前端可访问的图片地址。
     *
     * @return 本地图片地址
     */
    public String getPublicUrl() {
        return publicUrl;
    }

    /**
     * 获取上游服务返回的图片地址。
     *
     * @return 上游图片地址
     */
    public String getSourceImageUrl() {
        return sourceImageUrl;
    }
}

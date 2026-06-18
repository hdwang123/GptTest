package org.example.chatgpt.model;

/**
 * 图片生成结果，记录前端访问地址和上游图片来源地址。
 */
public class ImageResult {

    private final String publicUrl;

    private final String sourceImageUrl;

    /**
     * 创建图片生成结果。
     *
     * @param publicUrl      前端可访问的本地图片地址
     * @param sourceImageUrl 上游服务返回的图片地址
     */
    public ImageResult(String publicUrl, String sourceImageUrl) {
        this.publicUrl = publicUrl;
        this.sourceImageUrl = sourceImageUrl;
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

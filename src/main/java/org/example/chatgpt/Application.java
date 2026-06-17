package org.example.chatgpt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 项目启动类。
 *
 * @author wanghuidong
 */
@EnableAsync
@SpringBootApplication
public class Application {

    /**
     * Spring Boot 应用入口方法。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

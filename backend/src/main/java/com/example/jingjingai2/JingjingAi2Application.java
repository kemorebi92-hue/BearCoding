package com.example.jingjingai2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
})
public class JingjingAi2Application {
    public static void main(String[] args) {
        // 工作区的“浏览本地文件夹”要在本机弹出 Swing 选择器。Spring Boot 默认把
        // java.awt.headless 设成 true（GraphicsEnvironment.isHeadless() 直接返回 true），
        // 必须在 SpringApplication.run 之前显式关掉；configureHeadlessProperty() 用
        // System.getProperty(key, default) 读取，已设置的值会被保留。
        // 真到无显示环境（服务器 / 无桌面会话），AWT 仍会报 headless，接口返回明确错误。
        System.setProperty("java.awt.headless", "false");
        SpringApplication.run(JingjingAi2Application.class, args);
    }
}

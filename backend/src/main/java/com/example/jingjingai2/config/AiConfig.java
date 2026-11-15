package com.example.jingjingai2.config;

import com.example.jingjingai2.utils.TerminalTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    /** SDK retries must not hide extra requests from the task ledger. */
    @Bean
    public org.springframework.retry.support.RetryTemplate retryTemplate() {
        return org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build();
    }
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(30).build();
    }

    @Bean
    public OpenAiChatModel openAiChatModel(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl) {
        boolean configured = apiKey != null && !apiKey.isBlank();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(configured ? baseUrl : "http://127.0.0.1")
                .apiKey(configured ? apiKey : "unset")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(OpenAiChatOptions.builder().model(configured ? "configured" : "unset").build())
                .build();
    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel, ChatMemory chatMemory, TerminalTool terminalTool) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是小熊，用户的专属编码助手（BearCoding）。你的工作是阅读和修改代码、编译与测试，并通过工具在本机完成编程任务。只使用「小熊」这个名字。\n\n重要规则：\n- 修改或创建文件时，优先使用文件工具(readFile / writeFile / editFile)，不要用 echo、sed 等命令去改文件；文件工具更安全可靠，且 editFile 会返回 diff 差异供确认\n- 查看文件内容用 readFile(支持 offset/limit 只读部分)；新建或整体覆盖文件用 writeFile；只改其中几行用 editFile(必须保证 oldString 与原文完全一致，返回的是 diff)\n- 执行命令、编译、运行 git 等用 executeCommand\n- 查看目录结构用 listDirectory\n- 绝对不要编造结果，拿不准就调用工具去获取真实信息\n- 对于普通聊天（问候、闲聊）不需要调用工具\n\n可用工具：\n- executeCommand：在终端执行任意命令\n- listDirectory：列出目录内容\n- readFile：读取文件内容(支持 offset/limit 分页)\n- writeFile：写入/创建文件(覆盖式)\n- editFile：精确修改文件某段内容，返回 diff")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(terminalTool)
                .build();
    }
}

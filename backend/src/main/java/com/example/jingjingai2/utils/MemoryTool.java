package com.example.jingjingai2.utils;

import com.example.jingjingai2.memory.WorkspaceMemory;
import com.example.jingjingai2.sandbox.WorkspaceContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 把跨会话仍有用的项目事实写入工作区 {@code memory/*.md}。
 *
 * <p>为什么单独做工具、不指望模型自己 writeFile：writeFile 什么都能写，模型经常忘了记进度；
 * 这个工具的名字和描述就是在说「这里才是长期记忆」，并且写完会明确告诉它下次对话会自动带上。
 *
 * <p>工作区从 {@link WorkspaceContext} 取（和文件工具同一套 ThreadLocal），不让模型传路径。
 */
@Component
public class MemoryTool {

    private final WorkspaceMemory workspaceMemory;

    public MemoryTool(WorkspaceMemory workspaceMemory) {
        this.workspaceMemory = workspaceMemory;
    }

    @Tool(description = "【长期记忆】把跨会话仍有用的项目事实写入当前工作区 memory/ 下的 markdown。"
            + "下次在这个工作区对话会自动带进上下文。"
            + "【该写】项目核心约定、已经做完什么、当前卡在哪、下一步做什么、用户明确说过的偏好。"
            + "【不该写】密钥/口令、整份代码、git 历史、一次闲聊、只对这一轮有用的过程草稿。"
            + "【怎么放】name 空或 MEMORY → 索引 MEMORY.md（进度+下一步放这里）；"
            + "专题用独立文件名，如 decisions.md、stack.md。"
            + "默认覆盖整篇；只想补一行就 mode=append。")
    public String saveMemory(
            @ToolParam(description = "专题文件名，例如 MEMORY.md / progress.md / decisions。"
                    + "空 = 写索引 MEMORY.md。不要带路径。") String name,
            @ToolParam(description = "要记住的正文（markdown）。索引建议用：核心 / 当前进度 / 下一步 三节。")
            String content,
            @ToolParam(description = "overwrite=整篇覆盖（默认）；append=追加到文件末尾",
                    required = false) String mode) {
        Path workspace = WorkspaceContext.get();
        boolean append = mode != null && "append".equalsIgnoreCase(mode.trim());
        return workspaceMemory.save(workspace, name, content, append);
    }
}

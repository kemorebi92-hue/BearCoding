package com.example.jingjingai2;

import com.example.jingjingai2.permission.AllowAllGate;
import com.example.jingjingai2.tool.ToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具分发测试：验证"注册表 + 反射"替代 if/else 后，按名字找工具的行为仍然正确。
 *
 * 这段逻辑原来埋在 ChatController 的 private 方法里无法测试，
 * 抽出 ToolDispatcher 后即可覆盖——这是"为可测试性做重构"的直接收益。
 *
 * <p>本类只验证"按名字找 + 执行"，不验证审批，所以显式安装一个放行一切的网关
 * （{@link AllowAllGate}）。不再依赖"没有网关 = 不需要授权"这条已经被关掉的 fail-open。
 */
class ToolDispatcherTest {

    @BeforeEach
    void installGate() {
        AllowAllGate.install();
    }

    @AfterEach
    void uninstallGate() {
        AllowAllGate.uninstall();
    }

    /** 构造一个最小的 ToolCallback 测试替身 */
    private static ToolCallback stub(String name) {
        ToolDefinition def = new ToolDefinition() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub of " + name; }
            @Override public String inputSchema() { return "{}"; }
        };
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String arguments) { return "executed:" + name; }
        };
    }

    private final ToolCallback[] registry = {
            stub("executeCommand"),
            stub("readFile"),
            stub("writeFile"),
            stub("editFile")
    };

    @Test
    @DisplayName("按注册名精确命中")
    void findsByName() {
        ToolCallback cb = ToolDispatcher.find(registry, "readFile");
        assertNotNull(cb);
        assertEquals("readFile", cb.getToolDefinition().name());
    }

    @Test
    @DisplayName("大小写不敏感（不同模型生成的大小写可能不同）")
    void ignoresCase() {
        assertNotNull(ToolDispatcher.find(registry, "READFILE"));
        assertNotNull(ToolDispatcher.find(registry, "readfile"));
    }

    @Test
    @DisplayName("兼容下划线命名（read_file 也能命中 readFile）")
    void handlesUnderscoreVariants() {
        assertNotNull(ToolDispatcher.find(registry, "read_file"),
                "模型可能输出 read_file，应能匹配到 readFile");
        assertNotNull(ToolDispatcher.find(registry, "execute_command"));
    }

    @Test
    @DisplayName("找不到时返回 null，不抛异常")
    void returnsNullWhenNotFound() {
        assertNull(ToolDispatcher.find(registry, "deleteEverything"));
        assertNull(ToolDispatcher.find(registry, ""));
    }

    @Test
    @DisplayName("入参为空时安全返回 null")
    void handlesNullInput() {
        assertNull(ToolDispatcher.find(null, "readFile"));
        assertNull(ToolDispatcher.find(registry, null));
    }

    @Test
    @DisplayName("dispatch 命中时执行工具并返回结果")
    void dispatchExecutesTool() {
        String result = ToolDispatcher.dispatch(registry, "editFile", "{}");
        assertEquals("executed:editFile", result);
    }

    @Test
    @DisplayName("dispatch 未命中时返回明确错误文案（让模型能自我纠正，而不是崩溃）")
    void dispatchReturnsErrorTextWhenMissing() {
        String result = ToolDispatcher.dispatch(registry, "unknownTool", "{}");
        assertNotNull(result);
        assertTrue(result.contains("未知的工具"), "应返回可回传给模型的错误文案");
    }

    @Test
    @DisplayName("新增工具后无需改动分发逻辑（开闭原则）")
    void newToolWorksWithoutChangingDispatcher() {
        ToolCallback[] extended = {
                stub("readFile"),
                stub("grepSearch")   // 模拟"新增一个工具"
        };
        //分发器代码零改动即可命中新工具
        assertEquals("executed:grepSearch",
                ToolDispatcher.dispatch(extended, "grepSearch", "{}"));
    }

    @Test
    @DisplayName("mcp__ 必须精确匹配：去下划线不能让 read_file 撞上 readfile")
    void mcpNamesExactMatchOnly() {
        ToolCallback[] registry = {
                stub("mcp__fs__read_file"),
                stub("mcp__fs__readfile")
        };
        assertEquals("mcp__fs__read_file",
                ToolDispatcher.find(registry, "mcp__fs__read_file").getToolDefinition().name());
        assertEquals("mcp__fs__readfile",
                ToolDispatcher.find(registry, "mcp__fs__readfile").getToolDefinition().name());
        assertNull(ToolDispatcher.find(registry, "mcp__fs__READ_FILE"), "MCP 不做大小写模糊");
        assertNull(ToolDispatcher.find(registry, "mcp__other__echo"));
        assertEquals("executed:mcp__fs__read_file",
                ToolDispatcher.dispatch(registry, "mcp__fs__read_file", "{}"));
    }
}

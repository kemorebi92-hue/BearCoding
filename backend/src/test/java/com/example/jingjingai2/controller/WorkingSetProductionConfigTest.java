package com.example.jingjingai2.controller;

import com.example.jingjingai2.DTO.MessageRecord;
import com.example.jingjingai2.context.ContextProjectionPolicy;
import com.example.jingjingai2.context.ContextSourceLedger;
import com.example.jingjingai2.context.MessageProtocolValidator;
import com.example.jingjingai2.context.PromptView;
import com.example.jingjingai2.context.ToolHistoryCompactor;
import com.example.jingjingai2.mcp.McpRoundActivation;
import com.example.jingjingai2.permission.McpPermissionExplainer;
import com.example.jingjingai2.service.ConversationHistoryService;
import com.example.jingjingai2.tool.ToolArtifactRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M0 + M2 的生产默认路径验证：不是直接 new 控制器，而是让 Spring 真正加载
 * {@code src/main/resources/application.yml} 并把 {@code @Value} 注入控制器。
 *
 * <p>证明三件事：
 * <ol>
 *   <li>YAML 与 Java 的 {@code working-set.enabled} 默认值一致（true）；</li>
 *   <li>默认配置下聊天确实进入工作集分支（系统提示带检索指引）；</li>
 *   <li>默认软预算下，模型调用前的投影确实改写了最终 Prompt：较早、可安全恢复的
 *       工具正文换成省略标记，最近结果和配对保留；超过硬窗口的普通请求不发送。</li>
 * </ol>
 */
class WorkingSetProductionConfigTest {

    @TempDir
    Path tmp;

    private static final int BODY_REPEAT = 9000; // 每条约 18000 个中文字符 ≈ 18000 token

    @Test
    @DisplayName("生产 application.yml 加载后，工作集默认开启并注入真实控制器")
    void productionConfigEnablesWorkingSet() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("好的", Map.of(), List.of());
        withProductionConfig(controllerFactory(fake, ChatEntryPromptViewTest.historyMock()), controller -> {
            assertTrue(booleanField(controller, "workingSetEnabled"), "YAML 与 Java 默认值必须都是 true");
            assertTrue(booleanField(controller, "workingSetShadowEnabled"));
            assertEquals(64000, intField(controller, "workingSetSoftBudget"));
            assertEquals("history", stringField(controller, "compactScope"), "默认仍是 history 口径");
        });
    }

    @Test
    @DisplayName("默认配置下聊天进入工作集分支：系统提示带固定检索指引")
    void defaultConfigUsesWorkingSetPrompt() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("好的", Map.of(), List.of());
        withProductionConfig(controllerFactory(fake, ChatEntryPromptViewTest.historyMock()), controller -> {
            controller.chat(ChatEntryPromptViewTest.request("当前问题"),
                    ChatEntryPromptViewTest.httpRequest());

            assertFalse(fake.recorded.isEmpty(), "应至少发出一次模型请求");
            String system = fake.recorded.get(0).get(0).getText();
            assertTrue(system.contains("历史检索"), "只有工作集真正启用才会附加这段指引");
        });
    }

    @Test
    @DisplayName("默认配置的投影入口真的改写了最终 Prompt：旧工具正文退休，最近结果保留")
    void defaultProjectionRetiresOldToolBody() throws Exception {
        List<Message> messages = fourToolRoundPrompt();
        ContextSourceLedger ledger = ledgerFor(messages);
        List<String> beforeBodies = bodiesOf(messages);

        withProductionConfig(controllerFactory(new ChatEntryPromptViewTest.FakeModel(),
                        ChatEntryPromptViewTest.historyMock()),
                controller -> {
                    PromptView view = controller.projectBeforeModelForTest(
                            ledger, messages, new ToolCallback[0], 5, "conv-prod");

                    List<Message> outgoing = view.messages();
                    assertFalse(sameBodies(messages, outgoing), "最终 Prompt 必须真的变了");

                    String bodies = toolBodies(outgoing);
                    assertFalse(bodies.contains(body(1)), "较早的第 1 轮结果应退出正文");
                    assertFalse(bodies.contains(body(2)), "较早的第 2 轮结果应退出正文");
                    assertTrue(bodies.contains(body(3)), "最近一轮结果必须保留");
                    assertTrue(bodies.contains(body(4)), "最新工具结果必须保留");
                    assertTrue(bodies.contains(ToolHistoryCompactor.MARKER_PREFIX), "应换成省略标记");
                    assertNotNull(ToolArtifactRef.parse(bodies), "标记里必须带可验证签名引用");

                    for (int r = 1; r <= 4; r++) {
                        assertTrue(hasCall(outgoing, "call-" + r), "tool_call 必须留下: call-" + r);
                    }
                    assertTrue(MessageProtocolValidator.check(outgoing).ok(), "退休后协议仍完整");

                    for (int r = 1; r <= 4; r++) {
                        assertEquals(beforeBodies.get(r - 1), bodyOf(messages, "call-" + r),
                                "原始执行消息不能被改写");
                    }
                    assertTrue(ledger.get("tool:call-1").fullText().contains(body(1)), "源账本原文不变");

                    List<ContextProjectionPolicy.Decision> decisions = decisionsOf(ledger, messages);
                    assertTrue(decisions.stream().anyMatch(d -> "tool:call-1".equals(d.id())
                            && d.kind() == ContextProjectionPolicy.DecisionKind.RETIRE));
                });
    }

    @Test
    @DisplayName("生产路径：沙箱读不到的快照不退休，正文照发（M3 可读性闸门真的接上了）")
    void unreadableSnapshotIsNotRetiredInProduction() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        messages.add(new UserMessage("当前问题"));
        for (int r = 1; r <= 4; r++) {
            // @TempDir 在 Agent 工作区之外：文件在操作系统上存在，但当前沙箱不允许读。
            Path file = tmp.resolve("outside-snapshot-" + r + ".txt");
            String body = body(r);
            Files.writeString(file, body, StandardCharsets.UTF_8);
            messages.add(new AssistantMessage("", Map.of(), List.of(
                    new AssistantMessage.ToolCall("call-" + r, "function", "readFile",
                            "{\"path\":\"f" + r + ".txt\"}"))));
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("call-" + r, "readFile",
                            body + ToolArtifactRef.marker(file.toString(), body.length(), "readFile",
                                    sha256(body))))));
        }
        ContextSourceLedger ledger = ledgerFor(messages);

        withProductionConfig(controllerFactory(new ChatEntryPromptViewTest.FakeModel(),
                        ChatEntryPromptViewTest.historyMock()),
                controller -> {
                    PromptView view = controller.projectBeforeModelForTest(
                            ledger, messages, new ToolCallback[0], 5, "conv-prod");

                    assertTrue(sameBodies(messages, view.messages()),
                            "读不到的快照不能换成引用，实际 Prompt 必须保持原样");
                    assertTrue(toolBodies(view.messages()).contains(body(1)),
                            "不可恢复的工具正文必须原样留下");
                    // 用与控制器相同的沙箱根探测，确认原因确实是「当前沙箱不允许读」。
                    com.example.jingjingai2.utils.FileTool sameSandbox =
                            new com.example.jingjingai2.utils.FileTool(
                                    new com.example.jingjingai2.sandbox.PathSandbox("target/test-ws"));
                    List<ContextProjectionPolicy.Decision> decisions =
                            decisionsOf(ledger, messages, sameSandbox::snapshotStatus);
                    assertTrue(decisions.stream().anyMatch(d -> "tool:call-1".equals(d.id())
                                    && "TOOL_RESULT_SNAPSHOT_UNAUTHORIZED".equals(d.reason())),
                            "原因必须写明是「当前沙箱不允许读」: " + decisions);
                });
    }

    @Test
    @DisplayName("投影失败也不能把超窗请求发出去：兜底硬窗口检查仍然生效")
    void projectionFailureStillStopsOversizedRequest() {
        String huge = "字".repeat(140_000);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统"));
        messages.add(new UserMessage(huge));
        // 孤儿工具结果：没有对应 tool_call，投影后的协议检查会失败。
        messages.add(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("orphan", "readFile", "正文"))));
        ContextSourceLedger ledger = new ContextSourceLedger("conv-fail");
        ledger.absorb(messages, 0, null);

        withProductionConfig(controllerFactory(new ChatEntryPromptViewTest.FakeModel(),
                        ChatEntryPromptViewTest.historyMock()),
                controller -> {
                    PromptView view = controller.projectBeforeModelForTest(
                            ledger, messages, new ToolCallback[0], 5, "conv-fail");

                    assertEquals(1, view.messages().size(), "投影失败 + 超窗时必须只留下停止说明");
                    assertTrue(view.messages().get(0).getText().contains("超过模型窗口"),
                            view.messages().get(0).getText());
                });
    }

    @Test
    @DisplayName("没有摘要失败标记时，超过硬窗口的普通请求仍被拒绝发送")
    void oversizedRequestStopsWithoutSummaryFailure() {
        ChatEntryPromptViewTest.FakeModel fake = new ChatEntryPromptViewTest.FakeModel();
        fake.nonStreamingAnswer = new AssistantMessage("不应发送", Map.of(), List.of());
        ConversationHistoryService history = ChatEntryPromptViewTest.historyMock();
        String huge = "字".repeat(140_000);
        when(history.getHistory(any(), any())).thenReturn(List.of(new MessageRecord("user", huge)));

        withProductionConfig(controllerFactory(fake, history), controller -> {
            com.example.jingjingai2.DTO.ChatResponse response =
                    controller.chat(ChatEntryPromptViewTest.request(huge), ChatEntryPromptViewTest.httpRequest());

            assertEquals(0, fake.calls.get(), "超窗请求不得发送给模型");
            assertTrue(response.getMessage().contains("超过模型窗口"), response.getMessage());
        });
    }

    // ===================== production config loading =====================

    private interface CheckedConsumer<T> {
        void accept(T value) throws Exception;
    }

    private static void withProductionConfig(Supplier<ChatController> controller,
                                             CheckedConsumer<ChatController> checks) {
        new ApplicationContextRunner()
                .withInitializer(context -> loadApplicationYml(context.getEnvironment()))
                .withBean(McpPermissionExplainer.class, () -> mock(McpPermissionExplainer.class))
                .withBean(McpRoundActivation.class, McpRoundActivation::new)
                .withBean("chatController", ChatController.class, controller)
                .run(context -> checks.accept(context.getBean("chatController", ChatController.class)));
    }

    private static void loadApplicationYml(ConfigurableEnvironment environment) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            for (int i = sources.size() - 1; i >= 0; i--) {
                environment.getPropertySources().addFirst(sources.get(i));
            }
            // 这些用例都在“系统模型已配置”的前提下驱动聊天；空库引导另有专门测试。
            // 必须放在 application.yml 之后 addFirst，否则会被 ${DEEPSEEK_API_KEY:} 的空值覆盖。
            environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "testSystemModel", Map.of("spring.ai.openai.api-key", "test-system-model-key")));
        } catch (IOException e) {
            throw new IllegalStateException("加载生产 application.yml 失败", e);
        }
    }

    private static Supplier<ChatController> controllerFactory(ChatEntryPromptViewTest.FakeModel fake,
                                                              ConversationHistoryService history) {
        return () -> ChatEntryPromptViewTest.controller(fake, history);
    }

    // ===================== prompt / ledger helpers =====================

    private List<Message> fourToolRoundPrompt() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        messages.add(new UserMessage("当前问题"));
        for (int r = 1; r <= 4; r++) {
            messages.add(new AssistantMessage("", Map.of(), List.of(
                    new AssistantMessage.ToolCall("call-" + r, "function", "readFile",
                            "{\"path\":\"f" + r + ".txt\"}"))));
            messages.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse("call-" + r, "readFile", signedBody(r)))));
        }
        return messages;
    }

    private ContextSourceLedger ledgerFor(List<Message> messages) {
        ContextSourceLedger ledger = new ContextSourceLedger("conv-prod");
        int firstToolAt = messages.size() - 8;
        int round = 1;
        for (int i = 0; i < 4; i++) {
            ledger.absorb(new ArrayList<>(messages.subList(0, firstToolAt + (i + 1) * 2)), round++, null);
        }
        ledger.absorb(messages, round, null);
        return ledger;
    }

    private String signedBody(int r) throws Exception {
        String body = body(r);
        // 快照必须落在 Agent 真的能读到的位置：生产投影会用 FileTool 的沙箱探测可读性，
        // 放在工作区外（例如系统临时目录）的快照不再被视为「可靠可恢复」。
        Path file = Files.createDirectories(workspaceSnapshots()).resolve("prod-snapshot-" + r + ".txt");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return body + ToolArtifactRef.marker(file.toString(), body.length(), "readFile", sha256(body));
    }

    /** 与 {@code ChatEntryPromptViewTest.controller} 里 PathSandbox 的根保持一致。 */
    private static Path workspaceSnapshots() {
        return Path.of("target", "test-ws", "m3-prod-snapshots").toAbsolutePath().normalize();
    }

    private static String body(int r) {
        return "工具结果" + r + "：" + "内容".repeat(BODY_REPEAT);
    }

    private static List<ContextProjectionPolicy.Decision> decisionsOf(ContextSourceLedger ledger,
                                                                      List<Message> messages) {
        return ContextProjectionPolicy.project(ledger, messages, null, 5, 0, 64000, 0, true, false)
                .decisions();
    }

    private static List<ContextProjectionPolicy.Decision> decisionsOf(ContextSourceLedger ledger,
                                                                      List<Message> messages,
                                                                      com.example.jingjingai2.context.ToolSnapshotProbe probe) {
        return ContextProjectionPolicy.project(ledger, messages, null, 5, 0, 64000, 0, true, false, probe)
                .decisions();
    }

    // ===================== assertions / reflection =====================

    private static boolean booleanField(ChatController controller, String name) {
        return (Boolean) field(controller, name);
    }

    private static int intField(ChatController controller, String name) {
        return (Integer) field(controller, name);
    }

    private static String stringField(ChatController controller, String name) {
        return (String) field(controller, name);
    }

    private static Object field(ChatController controller, String name) {
        try {
            java.lang.reflect.Field field = ChatController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(controller);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean sameBodies(List<Message> left, List<Message> right) {
        return toolBodies(left).equals(toolBodies(right));
    }

    private static String toolBodies(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (String body : bodiesOf(messages)) out.append(body).append('\n');
        return out.toString();
    }

    private static List<String> bodiesOf(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage tool && tool.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    out.add(response.responseData());
                }
            }
        }
        return out;
    }

    private static String bodyOf(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage tool) || tool.getResponses() == null) continue;
            for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                if (response != null && id.equals(response.id())) return response.responseData();
            }
        }
        return null;
    }

    private static boolean hasCall(List<Message> messages, String id) {
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant) || assistant.getToolCalls() == null) continue;
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (call != null && id.equals(call.id())) return true;
            }
        }
        return false;
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}

package com.example.jingjingai2.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 漂移信号 + 分级提醒的规则测试。
 *
 * 为什么必须测：这套机制是**会主动打断模型**的（HARD 还会强制它调工具），
 * 判错两个方向都难受 —— 判太松等于没做，判太紧会变成"每轮都在唠叨、把简单任务也逼去规划"。
 * 尤其"改完再读不算重复读"这类反例，必须靠单测钉死（写错就会误报，把正常流程打断）。
 * 纯逻辑测试：不依赖 Spring、不联网、不读真实文件（文件版本用假实现注入）。
 */
class PlanNudgePolicyTest {

    private static PlanNudgePolicy.Config cfg() {
        return new PlanNudgePolicy.Config();
    }

    private static PlanNudgePolicy.Signals detect(List<PlanNudgePolicy.FileOp> ops, int verifyFails,
                                                  int rounds, String text) {
        return PlanNudgePolicy.detect(ops, verifyFails, rounds, text, cfg(), null, null);
    }

    private static List<PlanNudgePolicy.FileOp> ops(String... toolThenPath) {
        List<PlanNudgePolicy.FileOp> list = new ArrayList<>();
        for (int i = 0; i < toolThenPath.length; i += 2) {
            list.add(new PlanNudgePolicy.FileOp(toolThenPath[i], toolThenPath[i + 1]));
        }
        return list;
    }

    // ==================== 各单信号 ====================

    @Test
    @DisplayName("信号1：被修改的**不同**文件数 ≥3 才算；读操作不计入")
    void manyFilesOnlyCountsWrites() {
        List<PlanNudgePolicy.FileOp> two = ops("createFile", "a.java", "editFile", "b.java");
        assertFalse(detect(two, 0, 1, "").manyFiles, "只改 2 个文件 → 不够阈值");

        // 输出参数应回报"被修改的不同文件数"
        int[] counter = new int[1];
        PlanNudgePolicy.detect(two, 0, 1, "", cfg(), null, counter);
        assertEquals(2, counter[0]);

        List<PlanNudgePolicy.FileOp> three = ops("createFile", "a.java", "editFile", "b.java", "writeFile", "c.java");
        assertTrue(detect(three, 0, 1, "").manyFiles);

        // 同一个文件改 3 次只算 1 个文件
        List<PlanNudgePolicy.FileOp> same = ops("editFile", "a.java", "editFile", "a.java", "editFile", "a.java");
        assertFalse(detect(same, 0, 1, "").manyFiles, "同一个文件改多次不算'多文件'");

        // 纯读 3 个不同文件 → 不算改文件
        List<PlanNudgePolicy.FileOp> reads = ops("readFile", "a.java", "readFile", "b.java", "readFile", "c.java");
        assertFalse(detect(reads, 0, 1, "").manyFiles, "读操作不该计入'已修改文件'");
    }

    @Test
    @DisplayName("信号2：编译/校验失败累计 ≥2 次")
    void verifyFailuresSignal() {
        assertFalse(detect(List.of(), 1, 1, "").verifyFailures);
        assertTrue(detect(List.of(), 2, 1, "").verifyFailures);
        assertTrue(detect(List.of(), 5, 1, "").verifyFailures);
    }

    @Test
    @DisplayName("信号4：累计轮数 ≥8")
    void manyRoundsSignal() {
        assertFalse(detect(List.of(), 0, 7, "").manyRounds);
        assertTrue(detect(List.of(), 0, 8, "").manyRounds);
    }

    @Test
    @DisplayName("信号5：长文本里出现明显的步骤枚举（数字序号或 '首先/然后/最后'）")
    void implicitPlanSignal() {
        // 注意：判定要求"长文本"（默认 ≥120 字）—— 这里刻意用贴近真实的长段落，
        // 太短的例子本来就该放过（短句里的"1."多半是列表格式或数字，不是隐含规划）
        String numbered = "我打算这么做：\n"
                + "1. 先删掉旧的 inventory 目录，顺便把里面残留的编译产物和旧备份一起清理干净，"
                + "避免后面读到过期的类文件；\n"
                + "2. 重新设计 Product 与 Inventory 两个类，补齐字段、构造方法和必要的业务方法，"
                + "确保两者职责划分清晰、不互相依赖；\n"
                + "3. 用 javac 编译全部源文件，如果报错就逐个定位修正，直到编译通过、没有警告；\n"
                + "4. 运行 Demo 验证输出是否符合预期，确认没问题之后再回来向你汇报结果。\n";
        assertTrue(PlanNudgePolicy.looksLikeImplicitPlan(numbered, cfg()), "数字序号枚举应被识别");

        String words = "关于这个任务，首先我要把现有代码的结构理清楚，看看这几个类之间是怎么组织和调用的；"
                + "然后我会重新设计数据模型，把字段和职责划分明确，避免后面反复改；"
                + "接着把业务逻辑补上，并且保证每个方法我都能单独解释清楚；"
                + "最后编译运行验证一遍，确认输出符合预期之后，再回来向你完整汇报我做了哪些改动。";
        assertTrue(PlanNudgePolicy.looksLikeImplicitPlan(words, cfg()), "'首先/然后/最后'枚举应被识别");

        assertFalse(PlanNudgePolicy.looksLikeImplicitPlan("好的，我这就开始做。", cfg()), "太短 → 不算");
        assertFalse(PlanNudgePolicy.looksLikeImplicitPlan(null, cfg()), "null 不能抛异常");
        assertFalse(PlanNudgePolicy.looksLikeImplicitPlan("   ", cfg()), "纯空白 → 不算");
    }

    // ==================== 重复读取（含反例） ====================

    @Test
    @DisplayName("信号3a：同一文件连续读两次（中间没有写它）→ 算重复读取")
    void repeatedReadDetected() {
        List<PlanNudgePolicy.FileOp> list = ops("readFile", "a.java", "readFile", "a.java");
        assertTrue(PlanNudgePolicy.hasRepeatedRead(list, null));

        List<PlanNudgePolicy.FileOp> withOther = ops(
                "readFile", "a.java", "readFile", "b.java", "readFile", "a.java");
        assertTrue(PlanNudgePolicy.hasRepeatedRead(withOther, null), "中间读别的文件不影响判定");
    }

    @Test
    @DisplayName("信号3b（反例）：读 → 写 → 读 = 「改完再读」，绝不算重复读取")
    void readAfterWriteIsNotRepeatedRead() {
        List<PlanNudgePolicy.FileOp> list = ops(
                "readFile", "a.java", "editFile", "a.java", "readFile", "a.java");
        assertFalse(PlanNudgePolicy.hasRepeatedRead(list, null),
                "改完再读是正常流程，误判会打断正常干活");

        List<PlanNudgePolicy.FileOp> list2 = ops(
                "readFile", "a.java", "createFile", "a.java", "readFile", "a.java");
        assertFalse(PlanNudgePolicy.hasRepeatedRead(list2, null));

        // 每个文件只读一次 → 没有重复
        assertFalse(PlanNudgePolicy.hasRepeatedRead(ops("readFile", "a.java", "readFile", "b.java"), null));
    }

    @Test
    @DisplayName("信号3c：版本解析器说文件已不存在 → 不算重复读取（兜底防误报）")
    void resolverGuardPreventsFalsePositive() {
        List<PlanNudgePolicy.FileOp> list = ops("readFile", "gone.java", "readFile", "gone.java");
        PlanNudgePolicy.FileVersionResolver goneResolver = path -> null;      // 文件已不在
        assertFalse(PlanNudgePolicy.hasRepeatedRead(list, goneResolver));

        PlanNudgePolicy.FileVersionResolver okResolver = path -> "mtime:1:size:100";
        assertTrue(PlanNudgePolicy.hasRepeatedRead(list, okResolver), "文件还在 → 确实是在原地重复读");
    }

    // ==================== 分级 / 冷却 / 一次性强制 ====================

    @Test
    @DisplayName("分级：0 信号不打扰；1 信号先 SOFT；SOFT 过还不建计划才升级 HARD；≥2 信号直接 HARD")
    void levelDecision() {
        PlanNudgePolicy.Signals one = detect(List.of(), 0, 8, "");          // 只有 manyRounds
        assertEquals(1, one.count());
        assertEquals(PlanNudgePolicy.Level.SOFT, PlanNudgePolicy.decide(one, false, false));
        assertEquals(PlanNudgePolicy.Level.HARD, PlanNudgePolicy.decide(one, true, false),
                "SOFT 提醒过一次仍不建计划 → 升级 HARD");

        PlanNudgePolicy.Signals two = detect(List.of(), 2, 8, "");          // verifyFailures + manyRounds
        assertEquals(2, two.count());
        assertEquals(PlanNudgePolicy.Level.HARD, PlanNudgePolicy.decide(two, false, false));

        PlanNudgePolicy.Signals none = detect(List.of(), 0, 1, "");
        assertEquals(0, none.count());
        assertEquals(PlanNudgePolicy.Level.NONE, PlanNudgePolicy.decide(none, false, false));
    }

    @Test
    @DisplayName("已经有计划 → 一律不提醒（哪怕信号命中一堆）")
    void hasPlanDisablesNudging() {
        PlanNudgePolicy.Signals many = detect(
                ops("createFile", "a.java", "editFile", "b.java", "writeFile", "c.java"), 5, 30, "");
        assertTrue(many.count() >= 2);
        assertEquals(PlanNudgePolicy.Level.NONE, PlanNudgePolicy.decide(many, false, true));
    }

    @Test
    @DisplayName("冷却期：触发后 cooldown 轮内不再触发")
    void cooldown() {
        assertTrue(PlanNudgePolicy.isCoolingDown(5, 5, 3), "同一轮 → 冷却中");
        assertTrue(PlanNudgePolicy.isCoolingDown(6, 5, 3));
        assertTrue(PlanNudgePolicy.isCoolingDown(7, 5, 3));
        assertFalse(PlanNudgePolicy.isCoolingDown(8, 5, 3), "隔满 3 轮 → 可以再触发");
        assertFalse(PlanNudgePolicy.isCoolingDown(0, Integer.MIN_VALUE / 2, 3), "从未触发过 → 不冷却");
    }

    @Test
    @DisplayName("HARD 只作用于被指定的那一轮：下轮自动恢复，不会被反复强制")
    void forceIsOneShot() {
        int forceRound = 5;
        assertTrue(PlanNudgePolicy.isForceRound(5, forceRound), "指定轮 → 强制");
        assertFalse(PlanNudgePolicy.isForceRound(6, forceRound), "下一轮必须恢复为 auto");
        assertFalse(PlanNudgePolicy.isForceRound(7, forceRound));
        assertFalse(PlanNudgePolicy.isForceRound(4, forceRound), "更早的轮不受影响");
        assertFalse(PlanNudgePolicy.isForceRound(5, -1), "未设置强制轮 → 任何时候都不强制");
    }

    @Test
    @DisplayName("提醒文案必须引用命中的具体信号（不能只说'你该规划了'）")
    void descriptionsReferenceConcreteSignals() {
        PlanNudgePolicy.Signals s = detect(
                ops("createFile", "a.java", "editFile", "b.java", "writeFile", "c.java"), 2, 9, "");
        List<String> desc = s.descriptions(3, 2, 9);
        assertTrue(desc.stream().anyMatch(d -> d.contains("3 个不同文件")));
        assertTrue(desc.stream().anyMatch(d -> d.contains("失败 2 次")));
        assertTrue(desc.stream().anyMatch(d -> d.contains("9 轮")));
        assertTrue(s.names().contains("manyFiles"));
        assertTrue(s.names().contains("verifyFailures"));
        assertTrue(s.names().contains("manyRounds"));
    }

    @Test
    @DisplayName("stripWriteTools 逃生舱：只摘'能改变工作区'的工具，读类/规划类必须留下")
    void strippableTools() {
        // 必须摘掉：写/编辑类 + 命令执行（命令同样能改文件、能跑编译器）
        // + spawnSubagent（implement 子代理同样能改工作区，留着它等于给 executeCommand 又开一个洞）
        for (String t : new String[]{"writeFile", "createFile", "editFile", "appendFile",
                "deletePath", "createDirectory", "movePath", "copyPath", "executeCommand",
                "spawnSubagent"}) {
            assertTrue(PlanNudgePolicy.isStrippableTool(t), t + " 应该被摘掉");
        }
        // 必须保留：读类（模型仍要能看代码）、联网、以及两个规划工具本身
        // + 用户记忆（记偏好不是偷偷写代码）
        for (String t : new String[]{"readFile", "listDirectory", "globFile", "grepFile",
                "webSearch", "createPlan", "updatePlanStep",
                "saveUserMemory", "forgetUserMemory"}) {
            assertFalse(PlanNudgePolicy.isStrippableTool(t),
                    t + " 不该被摘掉（逃生舱是逼它先规划，不是让它瞎猜）");
        }
        assertFalse(PlanNudgePolicy.isStrippableTool(null), "null 安全");
        assertTrue(PlanNudgePolicy.isStrippableTool("mcp__echo__echo"),
                "第一期所有 mcp__ 工具都应被 HARD 逃生舱摘掉");
        assertTrue(PlanNudgePolicy.isStrippableTool("mcp__fs__read_file"));
        assertTrue(PlanNudgePolicy.isStrippableTool("mcpToolSearch"),
                "mcpToolSearch 也要被 HARD 逃生舱摘掉");
    }

    @Test
    @DisplayName("强制轮：一次性消费；且必须能在下一轮被消费到（不能被本轮的 nudge 覆盖）")
    void consumeForceRound() {
        int[] fr = {-1};
        // 没有待强制轮 → 任何时候都不消费
        assertFalse(PlanNudgePolicy.consumeForceRound(3, fr));
        assertEquals(-1, fr[0]);

        // 第 2 轮（0 基 i=1）触发 HARD → 强制第 3 轮（i=2）
        fr[0] = 2;
        // 第 3 轮先消费：命中且清零（一次性）
        assertTrue(PlanNudgePolicy.consumeForceRound(2, fr), "被强制的这一轮必须能消费到");
        assertEquals(-1, fr[0], "消费后要清掉，避免反复强制");
        // 同一轮再问一次 → 不再命中（防止同一轮被强制两次）
        assertFalse(PlanNudgePolicy.consumeForceRound(2, fr));
        // 其它轮不该命中
        fr[0] = 5;
        assertFalse(PlanNudgePolicy.consumeForceRound(4, fr));
        assertEquals(5, fr[0], "不是强制轮时不能清掉待强制的轮号");

        assertFalse(PlanNudgePolicy.consumeForceRound(0, null), "null 安全");
        assertFalse(PlanNudgePolicy.consumeForceRound(0, new int[0]), "空数组安全");
    }

    @Test
    @DisplayName("回归：连续命中信号时，强制轮依然可被消费（老实现会被 applyPlanNudge 覆盖掉）")
    void forceRoundNotLostWhenSignalsFireEveryRound() {
        int[] fr = {-1};
        // 模拟 cooldown=0：第 1 轮 SOFT，第 2 轮起每轮都命中 → 每轮都想把强制轮往后推
        fr[0] = 2;                                        // 第 2 轮(i=1) 的 nudge 设置：强制第 3 轮
        assertEquals(2, fr[0]);
        // 第 3 轮(i=2)：**先消费**，再让 nudge 去设置更晚的轮号
        assertTrue(PlanNudgePolicy.consumeForceRound(2, fr), "第 3 轮必须是强制轮");
        assertEquals(-1, fr[0]);
        fr[0] = 3;                                        // 第 3 轮的 nudge 又触发 HARD → 强制第 4 轮
        // 第 4 轮(i=3) 同样能消费到 → 说明"每轮都命中"也不会把强制轮吃掉
        assertTrue(PlanNudgePolicy.consumeForceRound(3, fr));
        assertEquals(-1, fr[0]);
    }
}

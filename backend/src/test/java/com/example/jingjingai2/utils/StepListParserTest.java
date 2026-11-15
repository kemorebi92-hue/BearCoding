package com.example.jingjingai2.utils;

import com.example.jingjingai2.DTO.StepInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * flat 形态 createPlan 的解析器测试。
 *
 * 为什么重点测它：flat 形态把"结构化步骤"的责任从模型（填复杂 JSON schema）搬到了后端
 * （解析纯文本），所以解析器的健壮性就是这条路的成败关键 —— 模型输出什么样都得能接住。
 * 全部是纯函数测试，不依赖 Spring 上下文、不联网。
 */
class StepListParserTest {

    @Test
    @DisplayName("正常输入：一行一步，按顺序解析，expected 留空")
    void normalInput() {
        StepListParser.Result r = StepListParser.parse(
                "删除旧的 inventory 目录\n"
                + "创建 Product.java 与 Inventory.java\n"
                + "用 javac 编译并运行 Demo");

        assertEquals(3, r.steps.size());
        assertFalse(r.truncated);
        assertEquals("删除旧的 inventory 目录", r.steps.get(0).getDescription());
        assertEquals("用 javac 编译并运行 Demo", r.steps.get(2).getDescription());
        assertEquals("", r.steps.get(0).getExpected(), "没写 || 时 expected 应为空串");
    }

    @Test
    @DisplayName("带序号/项目符号：1. / 2、/ 3) / - / • / (4) 全都能剥掉（含多层叠加）")
    void stripsListMarkers() {
        StepListParser.Result r = StepListParser.parse(
                "1. 第一步\n"
                + "2、第二步\n"
                + "3) 第三步\n"
                + "- 第四步\n"
                + "• 第五步\n"
                + "(6) 第六步\n"
                + "- 1. 第七步（多层前缀）");

        assertEquals(7, r.steps.size());
        assertEquals("第一步", r.steps.get(0).getDescription());
        assertEquals("第二步", r.steps.get(1).getDescription());
        assertEquals("第三步", r.steps.get(2).getDescription());
        assertEquals("第四步", r.steps.get(3).getDescription());
        assertEquals("第五步", r.steps.get(4).getDescription());
        assertEquals("第六步", r.steps.get(5).getDescription());
        assertEquals("第七步（多层前缀）", r.steps.get(6).getDescription());
    }

    @Test
    @DisplayName("空行 / 纯空白行被忽略，不影响步骤顺序")
    void ignoresBlankLines() {
        StepListParser.Result r = StepListParser.parse(
                "第一步\n"
                + "\n"
                + "   \n"
                + "\t\n"
                + "第二步");

        assertEquals(2, r.steps.size());
        assertEquals("第一步", r.steps.get(0).getDescription());
        assertEquals("第二步", r.steps.get(1).getDescription());
    }

    @Test
    @DisplayName("超长：超过 MAX_STEPS 行 → 截断到上限并把 truncated 置为 true")
    void truncatesWhenTooLong() {
        StringBuilder sb = new StringBuilder();
        int total = StepListParser.MAX_STEPS + 5;
        for (int i = 1; i <= total; i++) {
            sb.append("步骤").append(i).append('\n');
        }

        StepListParser.Result r = StepListParser.parse(sb.toString());
        assertEquals(StepListParser.MAX_STEPS, r.steps.size(), "只保留前 MAX_STEPS 步");
        assertTrue(r.truncated, "必须告诉调用方被截断了");
        assertEquals("步骤1", r.steps.get(0).getDescription());
        assertEquals("步骤" + StepListParser.MAX_STEPS,
                r.steps.get(StepListParser.MAX_STEPS - 1).getDescription());
    }

    @Test
    @DisplayName("恰好等于上限时不报截断（边界）")
    void exactlyAtLimitIsNotTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= StepListParser.MAX_STEPS; i++) {
            sb.append("步骤").append(i).append('\n');
        }
        StepListParser.Result r = StepListParser.parse(sb.toString());
        assertEquals(StepListParser.MAX_STEPS, r.steps.size());
        assertFalse(r.truncated);
    }

    @Test
    @DisplayName("空输入：null / 空串 / 纯空白 → 空列表且不抛异常")
    void emptyInput() {
        assertTrue(StepListParser.parse(null).steps.isEmpty());
        assertFalse(StepListParser.parse(null).truncated);

        assertTrue(StepListParser.parse("").steps.isEmpty());
        assertTrue(StepListParser.parse("   ").steps.isEmpty());
        assertTrue(StepListParser.parse("\n\n  \n\t\n").steps.isEmpty());
    }

    @Test
    @DisplayName("用 || 分隔预期产出；没有 || 时 expected 为空")
    void splitsExpectedByPipes() {
        StepListParser.Result r = StepListParser.parse(
                "创建 Student.java 并编译通过 || out/Student.class 存在\n"
                + "写单元测试");

        assertEquals("创建 Student.java 并编译通过", r.steps.get(0).getDescription());
        assertEquals("out/Student.class 存在", r.steps.get(0).getExpected());
        assertEquals("写单元测试", r.steps.get(1).getDescription());
        assertEquals("", r.steps.get(1).getExpected());
    }

    @Test
    @DisplayName("不误伤：'1.5 倍' 这类内容里的 '1.' 不能被当成序号剥掉")
    void doesNotStripDecimalNumbers() {
        assertEquals("1.5 倍的内存占用", StepListParser.clean("1.5 倍的内存占用"));
        assertEquals("把超时改成 2.5 秒", StepListParser.clean("- 把超时改成 2.5 秒"));
    }

    @Test
    @DisplayName("只有序号、没有内容的行应当被忽略（清理后为空）")
    void ignoresMarkerOnlyLines() {
        StepListParser.Result r = StepListParser.parse(
                "1.\n"
                + "2. 真正的一步\n"
                + "-\n"
                + "•");

        assertEquals(1, r.steps.size());
        assertEquals("真正的一步", r.steps.get(0).getDescription());
    }

    @Test
    @DisplayName("解析结果可直接喂给计划模型（字段齐全、无 null）")
    void resultIsUsable() {
        StepListParser.Result r = StepListParser.parse("- 创建文件 || 文件存在");
        List<StepInput> steps = r.steps;
        assertEquals(1, steps.size());
        assertNotNull(steps.get(0).getDescription());
        assertNotNull(steps.get(0).getExpected());
    }
}

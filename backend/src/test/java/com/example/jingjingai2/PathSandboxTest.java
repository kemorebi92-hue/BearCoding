package com.example.jingjingai2;

import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.sandbox.SandboxViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路径沙箱测试：验证"路径逃逸"能被正确拦截。
 */
class PathSandboxTest {

    // 以 target/sbt 作为虚拟工作区根目录（不需要真实存在，只校验路径逻辑）
    private final PathSandbox sandbox = new PathSandbox("target/sbt");

    @Test
    @DisplayName("工作区内的相对路径正常放行")
    void allowsPathInsideRoot() {
        Path p = sandbox.resolveWithin("a/b.txt");
        assertTrue(p.isAbsolute());
        assertTrue(p.toString().replace('\\', '/').endsWith("target/sbt/a/b.txt"));
    }

    @Test
    @DisplayName("工作区内的绝对路径正常放行")
    void allowsAbsolutePathInsideRoot() {
        Path root = sandbox.root();
        Path p = sandbox.resolveWithin(root.resolve("x.txt").toString());
        assertEquals(root.resolve("x.txt"), p);
    }

    @Test
    @DisplayName(".. 目录穿越被拦截")
    void blocksParentTraversal() {
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin("../outside.txt"));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin("a/../../outside.txt"));
    }

    @Test
    @DisplayName("指向工作区外的绝对路径被拦截")
    void blocksAbsolutePathOutsideRoot() {
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin("C:/Windows/System32/config"));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin("D:/secret/file.txt"));
    }

    @Test
    @DisplayName("空路径 / null 被拦截")
    void blocksEmptyOrNull() {
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin(""));
        assertThrows(SandboxViolationException.class, () -> sandbox.resolveWithin(null));
    }
}

package com.example.jingjingai2.mcp.testdouble;

/**
 * EchoMcpServer --spawn-child 拉起的子孙：一直睡，用来断言 close() 会杀整棵树。
 */
public final class ChildSleeper {
    public static void main(String[] args) throws Exception {
        Thread.sleep(3_600_000L);
    }
}

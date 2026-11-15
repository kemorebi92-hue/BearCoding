package com.example.jingjingai2.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** T11 允许读的文件：part1.txt–part6.txt，以及任务要求写入的 ANSWER.txt。 */
public final class AllowedReads {

    private static final Pattern ALLOWED = Pattern.compile("(^|/)(part[1-6]\\.txt|ANSWER\\.txt)$");
    private static final Pattern PATH = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

    private AllowedReads() {}

    public static List<String> outside(List<JudgeContext.ToolCallView> calls) {
        List<String> outside = new ArrayList<>();
        if (calls == null) return outside;
        for (JudgeContext.ToolCallView call : calls) {
            if (call.toolName() == null || !"readFile".equalsIgnoreCase(call.toolName())) continue;
            String path = pathOf(call.arguments());
            if (path == null) continue;
            if (!ALLOWED.matcher(path.replace('\\', '/')).find()) outside.add(path);
        }
        return List.copyOf(outside);
    }

    public static String pathOf(String arguments) {
        if (arguments == null) return null;
        Matcher matcher = PATH.matcher(arguments);
        return matcher.find() ? matcher.group(1) : null;
    }
}

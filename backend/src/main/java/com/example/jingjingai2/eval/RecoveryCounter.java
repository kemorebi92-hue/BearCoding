package com.example.jingjingai2.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 压缩之后，再去碰压缩前已经读过的 part 文件，记一次恢复。
 * 认 readFile、grepFile、globFile 和命令行。按文件名认，不要求参数字符串完全一样，
 * 压缩那一轮里发生的读取也算（压缩记在这一轮的开头）。
 */
public final class RecoveryCounter {

    private static final Pattern PART = Pattern.compile("part[1-6]\\.txt");
    private static final Set<String> TOOLS = Set.of("readFile", "grepFile", "globFile", "executeCommand");

    private RecoveryCounter() {}

    public record Use(Integer turnIndex, String toolName, String arguments) {}

    public static int count(List<Use> uses, Integer firstCompactionTurn) {
        return calls(uses, firstCompactionTurn).size();
    }

    public static List<Use> calls(List<Use> uses, Integer firstCompactionTurn) {
        if (uses == null || firstCompactionTurn == null) return List.of();
        Set<String> before = new LinkedHashSet<>();
        for (Use use : uses) {
            if (use.turnIndex() == null || use.turnIndex() >= firstCompactionTurn) continue;
            before.addAll(partsIn(use.arguments()));
        }
        List<Use> again = new ArrayList<>();
        for (Use use : uses) {
            if (use.turnIndex() == null || use.turnIndex() < firstCompactionTurn) continue;
            if (use.toolName() == null || !TOOLS.contains(use.toolName())) continue;
            for (String part : partsIn(use.arguments())) {
                if (before.contains(part)) {
                    again.add(use);
                    break;
                }
            }
        }
        return List.copyOf(again);
    }

    static List<String> partsIn(String text) {
        if (text == null) return List.of();
        Matcher matcher = PART.matcher(text.replace('\\', '/'));
        List<String> found = new ArrayList<>();
        while (matcher.find()) found.add(matcher.group());
        return found;
    }
}

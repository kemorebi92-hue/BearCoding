package com.example.jingjingai2.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 把现有记忆行转成结构化候选（Phase 4-3A）。
 *
 * <p>只填现有数据里真有的字段：归属用户、类别、是否硬约束。
 * <b>不填</b>的字段保持空：主题（没有这个列）、有效状态（看不出来）、替代关系（看不出来）、
 * 工作区归属（用户记忆按 user_id 归属，工作区记忆的归属由调用方从文件所在目录给出，本类不猜）。
 *
 * <p>空着的字段在 {@link StructuredMemorySelector} 里会落到 UNCERTAIN，而不是被当成某个主题。
 */
public final class MemoryCandidateAdapter {

    private MemoryCandidateAdapter() {}

    /**
     * 一条用户记忆能提供的全部信息。对应表 user_memory 的真实列，
     * 没有 topic / status / supersedes 字段。
     */
    public record UserMemoryRow(Long userId, String category, String memKey, String content) {}

    /** 用户记忆：用户级，workspace 留空（跟着用户走），topic / status / supersedes 全部留空。 */
    public static StructuredMemorySelector.Candidate fromUserMemory(UserMemoryRow row) {
        boolean constraint = row.category != null && row.category.equalsIgnoreCase("constraint");
        String key = row.memKey == null ? "" : row.memKey;
        String id = "user:" + (row.category == null ? "" : row.category) + ":" + key;
        MemoryItem item = new MemoryItem(MemoryItem.Source.USER, id, row.category, key,
                row.content, constraint, null);
        return new StructuredMemorySelector.Candidate(item, row.userId, null, null,
                StructuredMemorySelector.Status.UNKNOWN, null);
    }

    /**
     * 一个工作区记忆文件能提供的全部信息：文件名和正文。
     * 文件名不是主题，不拿它填 topic。workspace 由调用方传入（读盘时知道文件在哪个工作区），不从正文推断。
     */
    public static StructuredMemorySelector.Candidate fromWorkspaceFile(String workspace, String filename, String content) {
        String name = filename == null ? "" : filename;
        MemoryItem item = new MemoryItem(MemoryItem.Source.WORKSPACE, "workspace:" + name,
                null, name, content, false, null);
        return new StructuredMemorySelector.Candidate(item, null, workspace, null,
                StructuredMemorySelector.Status.UNKNOWN, null);
    }

    public static List<StructuredMemorySelector.Candidate> fromUserMemories(List<UserMemoryRow> rows) {
        List<StructuredMemorySelector.Candidate> out = new ArrayList<>();
        if (rows == null) return out;
        for (UserMemoryRow row : rows) if (row != null) out.add(fromUserMemory(row));
        return out;
    }
}

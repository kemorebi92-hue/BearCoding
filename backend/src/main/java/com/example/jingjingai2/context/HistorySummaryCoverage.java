package com.example.jingjingai2.context;

import com.example.jingjingai2.DTO.MessageRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次历史摘要覆盖到的原始消息范围（Phase M1）。
 *
 * <p>它回答的是「这段摘要替代了哪些原文」，不是「摘要说了什么」：
 * <ul>
 *   <li>{@link #messageIds()}：被替代的 {@code conversation_message} 主键，时间正序；</li>
 *   <li>{@link #fingerprint()}：这些原文的确定性指纹（id + role + 正文）。原文被改写、删除或换序都会变，
 *       据此失效，而不是靠时间或「看起来差不多」；</li>
 *   <li>{@link #scope()}：会话作用域（{@code userId|conversationId}），防止跨会话混用；</li>
 *   <li>{@link #createdAtMillis()} / {@link #version()}：产生时间与同一会话内的第几版摘要；</li>
 *   <li>{@link #coveredTokensEst()} / {@link #summaryTokensEst()}：覆盖前估算与摘要消息（含包装说明）估算。</li>
 * </ul>
 *
 * <p>原始消息永远不删：这里的 id 就是 {@code searchContext operation=read locator=message|<id>} 的定位依据。
 * 这个记录只在请求内传递，可选的跨请求复用见 {@link HistorySummaryCache}（进程内、有界、非持久）。
 */
public record HistorySummaryCoverage(
        String scope,
        List<Long> messageIds,
        String fingerprint,
        String summary,
        int coveredTokensEst,
        int summaryTokensEst,
        long createdAtMillis,
        int version) {

    public HistorySummaryCoverage {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }

    public int coveredCount() {
        return messageIds.size();
    }

    public Long firstId() {
        return messageIds.isEmpty() ? null : messageIds.get(0);
    }

    public Long lastId() {
        return messageIds.isEmpty() ? null : messageIds.get(messageIds.size() - 1);
    }

    /** 没有 id、没有正文或没有指纹的覆盖记录不能用来替代原文。 */
    public boolean usable() {
        return !messageIds.isEmpty() && summary != null && !summary.isBlank()
                && fingerprint != null && !fingerprint.isBlank();
    }

    /**
     * 覆盖范围是否仍是这份历史的开头，且每条原文都没变。
     *
     * <p>只接受「前缀」：新消息只能追加在覆盖范围之后。若历史被删、被换序，或覆盖范围内的正文被改写，
     * 一律返回 false，由调用方丢弃旧摘要并重新判断，避免把旧摘要套在已经变了的原文上。
     */
    public boolean coversPrefix(List<MessageRecord> prior) {
        if (!usable() || prior == null || prior.size() < messageIds.size()) return false;
        List<MessageRecord> covered = prior.subList(0, messageIds.size());
        for (int i = 0; i < covered.size(); i++) {
            MessageRecord record = covered.get(i);
            if (record == null || record.getId() == null || !record.getId().equals(messageIds.get(i))) {
                return false;
            }
        }
        return fingerprint.equals(fingerprintOf(covered));
    }

    /** 覆盖范围的数据库 id，时间正序。任何一条缺 id 都返回 null：没有稳定身份就不做覆盖追踪。 */
    static List<Long> idsOf(List<MessageRecord> records) {
        if (records == null || records.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>(records.size());
        for (MessageRecord record : records) {
            if (record == null || record.getId() == null) return null;
            ids.add(record.getId());
        }
        return List.copyOf(ids);
    }

    /**
     * id + role + 正文的确定性指纹。不用对象地址、不用下标、不用时间戳：
     * 同一份历史在任何进程里都得到同一个值，正文一变就变。
     */
    static String fingerprintOf(List<MessageRecord> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int count = records == null ? 0 : records.size();
            digest.update((count + "|").getBytes(StandardCharsets.UTF_8));
            if (records != null) {
                for (MessageRecord record : records) {
                    String id = record == null || record.getId() == null ? "" : record.getId().toString();
                    String role = record == null || record.getRole() == null ? "" : record.getRole();
                    String content = record == null || record.getContent() == null ? "" : record.getContent();
                    digest.update((id + "\u0000" + role + "\u0000" + content + "\u0001").getBytes(StandardCharsets.UTF_8));
                }
            }
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest.digest()) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "fingerprint-error";
        }
    }

    /** 只给日志和统计用的范围标签，不含任何正文。 */
    static String rangeLabel(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "";
        Long first = ids.get(0);
        Long last = ids.get(ids.size() - 1);
        return first == null || last == null ? "" : first + "-" + last;
    }
}

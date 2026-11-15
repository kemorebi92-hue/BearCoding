package com.example.jingjingai2.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发请求前检查工具调用和工具结果是否按 id 配得上。
 * 只读，不改列表，不补结果，也不看正文。
 */
public final class MessageProtocolValidator {

    public enum Kind {
        BLANK_CALL_ID,
        DUPLICATE_CALL_ID,
        BLANK_RESPONSE_ID,
        DUPLICATE_RESPONSE_ID,
        ORPHAN_RESPONSE,
        RESPONSE_BEFORE_CALL,
        MISSING_RESPONSE
    }

    public record Issue(Kind kind, String toolCallId, int messageIndex) {}

    public record Result(List<Issue> issues) {
        public boolean ok() {
            return issues.isEmpty();
        }

        /** 只有错误类型、id 和位置，没有消息正文。 */
        public String summary() {
            if (issues.isEmpty()) return "ok";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < issues.size(); i++) {
                if (i > 0) sb.append("; ");
                Issue issue = issues.get(i);
                sb.append(issue.kind()).append(" id=").append(issue.toolCallId())
                        .append(" at=").append(issue.messageIndex());
            }
            return sb.toString();
        }
    }

    private MessageProtocolValidator() {}

    public static Result check(List<Message> messages) {
        List<Issue> issues = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return new Result(issues);
        }
        Map<String, Integer> callAt = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!(message instanceof AssistantMessage assistant)) continue;
            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            if (calls == null) continue;
            for (AssistantMessage.ToolCall call : calls) {
                String id = call == null ? null : call.id();
                if (id == null || id.isBlank()) {
                    issues.add(new Issue(Kind.BLANK_CALL_ID, "", i));
                    continue;
                }
                if (callAt.containsKey(id)) {
                    issues.add(new Issue(Kind.DUPLICATE_CALL_ID, id, i));
                    continue;
                }
                callAt.put(id, i);
            }
        }
        Map<String, Integer> answered = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!(message instanceof ToolResponseMessage tool)) continue;
            List<ToolResponseMessage.ToolResponse> responses = tool.getResponses();
            if (responses == null) continue;
            for (ToolResponseMessage.ToolResponse response : responses) {
                String id = response == null ? null : response.id();
                if (id == null || id.isBlank()) {
                    issues.add(new Issue(Kind.BLANK_RESPONSE_ID, "", i));
                    continue;
                }
                Integer declaredAt = callAt.get(id);
                if (declaredAt == null) {
                    issues.add(new Issue(Kind.ORPHAN_RESPONSE, id, i));
                    continue;
                }
                if (i < declaredAt) {
                    issues.add(new Issue(Kind.RESPONSE_BEFORE_CALL, id, i));
                    continue;
                }
                if (answered.containsKey(id)) {
                    issues.add(new Issue(Kind.DUPLICATE_RESPONSE_ID, id, i));
                    continue;
                }
                answered.put(id, i);
            }
        }
        for (Map.Entry<String, Integer> call : callAt.entrySet()) {
            if (!answered.containsKey(call.getKey())) {
                issues.add(new Issue(Kind.MISSING_RESPONSE, call.getKey(), call.getValue()));
            }
        }
        return new Result(List.copyOf(issues));
    }
}

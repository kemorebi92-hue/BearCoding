package com.example.jingjingai2.DTO;

import java.util.List;

public class ChatResponse {
    private java.util.Map<String, Object> execution;
    public java.util.Map<String, Object> getExecution() { return execution; }
    public void setExecution(java.util.Map<String, Object> execution) { this.execution = execution; }
    private String action;
    private String message;
    private List<SongItem> songs;
    private List<ToolCallRecord> toolCalls;
    private String toolUpdate;
    private boolean needsApproval;
    private String pendingCommand;
    private String conversationId;

    public ChatResponse() {}
    public ChatResponse(String action, String message) { this.action = action; this.message = message; }
    public ChatResponse(String action, String message, List<SongItem> songs) {
        this.action = action; this.message = message; this.songs = songs;
    }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<SongItem> getSongs() { return songs; }
    public void setSongs(List<SongItem> songs) { this.songs = songs; }
    public List<ToolCallRecord> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallRecord> toolCalls) { this.toolCalls = toolCalls; }
    public String getToolUpdate() { return toolUpdate; }
    public void setToolUpdate(String toolUpdate) { this.toolUpdate = toolUpdate; }
    public boolean isNeedsApproval() { return needsApproval; }
    public void setNeedsApproval(boolean needsApproval) { this.needsApproval = needsApproval; }
    public String getPendingCommand() { return pendingCommand; }
    public void setPendingCommand(String pendingCommand) { this.pendingCommand = pendingCommand; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public static class SongItem {
        private String id; private String name; private String artist; private String pic;
        public SongItem() {}
        public SongItem(String id, String name, String artist, String pic) {
            this.id = id; this.name = name; this.artist = artist; this.pic = pic;
        }
        public String getId() { return id; } public void setId(String id) { this.id = id; }
        public String getName() { return name; } public void setName(String name) { this.name = name; }
        public String getArtist() { return artist; } public void setArtist(String artist) { this.artist = artist; }
        public String getPic() { return pic; } public void setPic(String pic) { this.pic = pic; }
    }

    public static class ToolCallRecord {
        private String toolName; private String input; private String result;
        private boolean success; private long elapsedMs;
        public ToolCallRecord() {}
        public ToolCallRecord(String toolName, String input, String result, boolean success, long elapsedMs) {
            this.toolName = toolName; this.input = input; this.result = result;
            this.success = success; this.elapsedMs = elapsedMs;
        }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    }
}

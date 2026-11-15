package com.example.jingjingai2.mcp;

/**
 * 一次超限结果的处置结果。{@code text} 是交给模型的那段（预览 + 说明）。
 */
public final class McpSpill {

    public final boolean spilled;
    public final String text;
    public final String server;
    public final String tool;
    /** 落盘文件名（末段），不含目录。没落盘时为空串。 */
    public final String fileName;
    /** 绝对路径。只在后端用；SSE 和接口不外发。 */
    public final String path;
    public final int totalChars;
    public final int previewChars;
    /** 写盘失败时非空。这时 {@code text} 是旧截断的结果。 */
    public final String failure;

    private McpSpill(boolean spilled, String text, String server, String tool, String fileName,
                     String path, int totalChars, int previewChars, String failure) {
        this.spilled = spilled;
        this.text = text;
        this.server = server;
        this.tool = tool;
        this.fileName = fileName;
        this.path = path;
        this.totalChars = totalChars;
        this.previewChars = previewChars;
        this.failure = failure;
    }

    static McpSpill notSpilled(String text) {
        return new McpSpill(false, text, "", "", "", "", text == null ? 0 : text.length(), 0, null);
    }

    static McpSpill spilled(String text, String server, String tool, String fileName,
                            String path, int totalChars, int previewChars) {
        return new McpSpill(true, text, server, tool, fileName, path, totalChars, previewChars, null);
    }

    static McpSpill failed(String text, String server, String tool, String reason) {
        return new McpSpill(false, text, server, tool, "", "", 0, 0, reason);
    }
}

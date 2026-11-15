package com.example.jingjingai2.mcp;

/**
 * MCP Server {@code tools/list} 里的一条工具（清洗后的 schema，原始名字未加 mcp__ 前缀）。
 */
public final class McpRemoteTool {

    private final String originalName;
    private final String description;
    private final String inputSchemaJson;
    private final McpToolAnnotations annotations;

    public McpRemoteTool(String originalName, String description, String inputSchemaJson) {
        this(originalName, description, inputSchemaJson, null);
    }

    public McpRemoteTool(String originalName, String description, String inputSchemaJson,
                         McpToolAnnotations annotations) {
        this.originalName = originalName == null ? "" : originalName;
        this.description = description == null ? "" : description;
        this.inputSchemaJson = inputSchemaJson == null || inputSchemaJson.isBlank()
                ? "{\"type\":\"object\"}" : inputSchemaJson;
        this.annotations = annotations == null ? McpToolAnnotations.none() : annotations;
    }

    public String originalName() { return originalName; }
    public String description() { return description; }
    public String inputSchemaJson() { return inputSchemaJson; }
    public McpToolAnnotations annotations() { return annotations; }
}

package com.example.jingjingai2.mcp;

/**
 * 一次 tools/list 里的工具，含部署层有没有把它暴露给模型和前端配置。
 * {@code exposed=false} 的工具仍然出现在面板里，但不能改、也不下发。
 */
public final class McpListedTool {

    public final String server;
    public final String originalName;
    public final String exposedName;
    public final String description;
    public final String inputSchemaJson;
    public final McpToolAnnotations annotations;
    public final boolean exposed;

    public McpListedTool(String server, String originalName, String exposedName,
                         String description, String inputSchemaJson,
                         McpToolAnnotations annotations, boolean exposed) {
        this.server = server == null ? "" : server;
        this.originalName = originalName == null ? "" : originalName;
        this.exposedName = exposedName == null ? "" : exposedName;
        this.description = description == null ? "" : description;
        this.inputSchemaJson = inputSchemaJson == null ? "" : inputSchemaJson;
        this.annotations = annotations == null ? McpToolAnnotations.none() : annotations;
        this.exposed = exposed;
    }

    public String definitionHash() {
        return McpDefinitionHash.of(originalName, description, inputSchemaJson);
    }
}

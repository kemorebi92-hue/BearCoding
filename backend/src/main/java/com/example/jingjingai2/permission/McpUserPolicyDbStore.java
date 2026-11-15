package com.example.jingjingai2.permission;

import com.example.jingjingai2.entity.McpUserPolicy;
import com.example.jingjingai2.mapper.McpUserPolicyMapper;
import org.springframework.stereotype.Component;

/** 重启后还在。表在 schema.sql，启动时 IF NOT EXISTS。 */
@Component
public class McpUserPolicyDbStore implements McpUserPolicyStore {

    static final String SERVER_DEFAULT = "";

    private final McpUserPolicyMapper mapper;

    public McpUserPolicyDbStore(McpUserPolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void setTool(long userId, String server, String tool, String effect, String definitionHash) {
        mapper.upsert(userId, server, tool, effect, definitionHash);
    }

    @Override
    public void setServerDefault(long userId, String server, String effect) {
        mapper.upsert(userId, server, SERVER_DEFAULT, effect, null);
    }

    @Override
    public void clearServerDefault(long userId, String server) {
        mapper.deleteOne(userId, server, SERVER_DEFAULT);
    }

    @Override
    public void resetTools(long userId, String server) {
        mapper.deleteTools(userId, server);
    }

    @Override
    public String toolEffect(long userId, String server, String tool) {
        McpUserPolicy row = mapper.find(userId, server, tool);
        return row == null ? null : row.getEffect();
    }

    @Override
    public String toolHash(long userId, String server, String tool) {
        McpUserPolicy row = mapper.find(userId, server, tool);
        return row == null ? null : row.getDefinitionHash();
    }

    @Override
    public String serverDefault(long userId, String server) {
        McpUserPolicy row = mapper.find(userId, server, SERVER_DEFAULT);
        return row == null ? null : row.getEffect();
    }
}

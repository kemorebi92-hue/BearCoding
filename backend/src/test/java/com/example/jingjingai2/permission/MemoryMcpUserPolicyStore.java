package com.example.jingjingai2.permission;

import java.util.concurrent.ConcurrentHashMap;

/** 单测用。生产是 {@link McpUserPolicyDbStore}。 */
public final class MemoryMcpUserPolicyStore implements McpUserPolicyStore {

    private static final class Row {
        String effect;
        String hash;
    }

    private final ConcurrentHashMap<String, Row> rows = new ConcurrentHashMap<>();

    @Override
    public void setTool(long userId, String server, String tool, String effect, String definitionHash) {
        Row row = new Row();
        row.effect = effect;
        row.hash = definitionHash;
        rows.put(key(userId, server, tool), row);
    }

    @Override
    public void setServerDefault(long userId, String server, String effect) {
        Row row = new Row();
        row.effect = effect;
        rows.put(key(userId, server, ""), row);
    }

    @Override
    public void clearServerDefault(long userId, String server) {
        rows.remove(key(userId, server, ""));
    }

    @Override
    public void resetTools(long userId, String server) {
        String prefix = userId + "\0" + server + "\0";
        rows.keySet().removeIf(k -> k.startsWith(prefix) && !k.equals(prefix));
    }

    @Override
    public String toolEffect(long userId, String server, String tool) {
        Row row = rows.get(key(userId, server, tool));
        return row == null ? null : row.effect;
    }

    @Override
    public String toolHash(long userId, String server, String tool) {
        Row row = rows.get(key(userId, server, tool));
        return row == null ? null : row.hash;
    }

    @Override
    public String serverDefault(long userId, String server) {
        Row row = rows.get(key(userId, server, ""));
        return row == null ? null : row.effect;
    }

    private static String key(long userId, String server, String tool) {
        return userId + "\0" + server + "\0" + (tool == null ? "" : tool);
    }
}

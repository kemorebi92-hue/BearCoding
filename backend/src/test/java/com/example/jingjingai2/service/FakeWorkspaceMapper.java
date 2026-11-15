package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.Workspace;
import com.example.jingjingai2.mapper.WorkspaceMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 内存假 Mapper，对齐 WorkspaceMapper 的唯一约束与按用户过滤。 */
final class FakeWorkspaceMapper implements WorkspaceMapper {

    private final Map<Long, Workspace> byId = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public int insert(Workspace workspace) {
        String path = workspace.getBoundPath();
        Long userId = workspace.getUserId();
        for (Workspace w : byId.values()) {
            if (Objects.equals(w.getUserId(), userId) && Objects.equals(w.getBoundPath(), path)) {
                throw new RuntimeException("Duplicate entry '" + userId + "-" + path + "' for key uk_user_bound_path");
            }
        }
        workspace.setId(nextId++);
        Date now = new Date();
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        byId.put(workspace.getId(), copy(workspace));
        return 1;
    }

    @Override
    public Workspace selectById(Long id) {
        Workspace w = byId.get(id);
        return w == null ? null : copy(w);
    }

    @Override
    public Workspace selectByUserAndId(Long userId, Long id) {
        Workspace w = byId.get(id);
        if (w == null || !Objects.equals(w.getUserId(), userId)) return null;
        return copy(w);
    }

    @Override
    public Workspace selectByUserAndPath(Long userId, String boundPath) {
        for (Workspace w : byId.values()) {
            if (Objects.equals(w.getUserId(), userId) && Objects.equals(w.getBoundPath(), boundPath)) {
                return copy(w);
            }
        }
        return null;
    }

    @Override
    public List<Workspace> listByUser(Long userId) {
        List<Workspace> out = new ArrayList<>();
        for (Workspace w : byId.values()) {
            if (Objects.equals(w.getUserId(), userId)) out.add(copy(w));
        }
        out.sort(Comparator.comparing(Workspace::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Workspace::getId, Comparator.reverseOrder()));
        return out;
    }

    @Override
    public int updateName(Long userId, Long id, String name) {
        Workspace w = byId.get(id);
        if (w == null || !Objects.equals(w.getUserId(), userId)) return 0;
        w.setName(name);
        w.setUpdatedAt(new Date());
        return 1;
    }

    @Override
    public int deleteByUserAndId(Long userId, Long id) {
        Workspace w = byId.get(id);
        if (w == null || !Objects.equals(w.getUserId(), userId)) return 0;
        byId.remove(id);
        return 1;
    }

    @Override
    public int countWorkspaceTable() {
        return 1;
    }

    int size() {
        return byId.size();
    }

    private static Workspace copy(Workspace src) {
        Workspace d = new Workspace();
        d.setId(src.getId());
        d.setUserId(src.getUserId());
        d.setName(src.getName());
        d.setBoundPath(src.getBoundPath());
        d.setCreatedAt(src.getCreatedAt());
        d.setUpdatedAt(src.getUpdatedAt());
        return d;
    }
}

package com.example.jingjingai2.mapper;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.mapper.sql.McpServerConfigSqlProvider;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;

import java.util.List;

/**
 * 运行时 MCP 服务配置。归属是 {@code created_by}：{@code NULL}（等价于 0）表示系统/历史共享，
 * 只有具体 user_id 才算个人服务。
 *
 * <p>读接口按名字查（调用方必须自己确认归属），写接口一律带 owner 条件——
 * 光靠 Controller 判断不够，漏一个入口就是跨用户改写。
 */
@Mapper
public interface McpServerConfigMapper {

    @Insert("INSERT INTO mcp_server_config(name, command, args_json, env_json, disabled_tools_json, trust, " +
            "connect_timeout_seconds, max_result_chars, enabled, created_by, " +
            "transport, base_url, endpoint_path, auth_type) " +
            "VALUES(#{name}, #{command}, #{argsJson}, #{envJson}, #{disabledToolsJson}, #{trust}, " +
            "#{connectTimeoutSeconds}, #{maxResultChars}, #{enabled}, #{createdBy}, " +
            "#{transport}, #{baseUrl}, #{endpointPath}, #{authType})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(McpServerConfig config);

    /** 只改这个人自己的那一行；created_by 为空的历史行不会被普通用户改到。 */
    @UpdateProvider(type = McpServerConfigSqlProvider.class, method = "update")
    int update(McpServerConfig config);

    @Update("UPDATE mcp_server_config SET enabled=#{enabled} " +
            "WHERE name=#{name} AND created_by=#{ownerId}")
    int updateEnabled(@Param("ownerId") long ownerId,
                      @Param("name") String name,
                      @Param("enabled") boolean enabled);

    @Delete("DELETE FROM mcp_server_config WHERE name=#{name} AND created_by=#{ownerId}")
    int delete(@Param("ownerId") long ownerId, @Param("name") String name);

    @Select("SELECT id, name, created_by FROM mcp_server_config WHERE id=#{id}")
    McpServerConfig findById(@Param("id") long id);

    /**
     * 按名字查（不区分归属）。仅供需要「这个名字被谁占了」的检查与启动加载使用；
     * 改/删之前必须再确认 {@code created_by}。
     */
    @Select("SELECT id, name, command, args_json, env_json, disabled_tools_json, trust, " +
            "connect_timeout_seconds, max_result_chars, enabled, created_by, " +
            "transport, base_url, endpoint_path, auth_type " +
            "FROM mcp_server_config WHERE name=#{name} LIMIT 1")
    McpServerConfig findByName(@Param("name") String name);

    @Select("SELECT id, name, command, args_json, env_json, disabled_tools_json, trust, " +
            "connect_timeout_seconds, max_result_chars, enabled, created_by, " +
            "transport, base_url, endpoint_path, auth_type " +
            "FROM mcp_server_config WHERE name=#{name} AND created_by=#{ownerId} LIMIT 1")
    McpServerConfig findByOwnerAndName(@Param("ownerId") long ownerId, @Param("name") String name);

    /**
     * 有没有系统/历史共享行（{@code created_by} 为 0 或 NULL）。
     * 名字比较交给列自己的校对规则（{@code name = ?}），不在这里改大小写。
     * 只取判断所需的列，不读 command、args、env。
     */
    @Select("SELECT id, name, created_by FROM mcp_server_config " +
            "WHERE name = #{name} AND (created_by = 0 OR created_by IS NULL) LIMIT 1")
    McpServerConfig findSharedByName(@Param("name") String name);

    @Select("SELECT id, name, command, args_json, env_json, disabled_tools_json, trust, " +
            "connect_timeout_seconds, max_result_chars, enabled, created_by, " +
            "transport, base_url, endpoint_path, auth_type " +
            "FROM mcp_server_config ORDER BY id")
    List<McpServerConfig> findAll();

    @Select("SELECT id, name, command, args_json, env_json, disabled_tools_json, trust, " +
            "connect_timeout_seconds, max_result_chars, enabled, created_by, " +
            "transport, base_url, endpoint_path, auth_type " +
            "FROM mcp_server_config WHERE created_by=#{ownerId} ORDER BY id")
    List<McpServerConfig> findAllByOwner(@Param("ownerId") long ownerId);
}

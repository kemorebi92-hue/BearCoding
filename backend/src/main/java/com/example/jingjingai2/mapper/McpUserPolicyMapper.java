package com.example.jingjingai2.mapper;

import com.example.jingjingai2.entity.McpUserPolicy;
import com.example.jingjingai2.mapper.sql.McpUserPolicySqlProvider;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface McpUserPolicyMapper {

    @InsertProvider(type = McpUserPolicySqlProvider.class, method = "upsert")
    int upsert(@Param("userId") long userId,
               @Param("server") String server,
               @Param("tool") String tool,
               @Param("effect") String effect,
               @Param("definitionHash") String definitionHash);

    @Select("SELECT user_id, server_name, tool_name, effect, definition_hash " +
            "FROM mcp_user_policy WHERE user_id = #{userId} AND server_name = #{server} " +
            "AND tool_name = #{tool} LIMIT 1")
    McpUserPolicy find(@Param("userId") long userId,
                       @Param("server") String server,
                       @Param("tool") String tool);

    @Delete("DELETE FROM mcp_user_policy WHERE user_id = #{userId} AND server_name = #{server} " +
            "AND tool_name = #{tool}")
    int deleteOne(@Param("userId") long userId,
                  @Param("server") String server,
                  @Param("tool") String tool);

    @Delete("DELETE FROM mcp_user_policy WHERE user_id = #{userId} AND server_name = #{server} " +
            "AND tool_name <> ''")
    int deleteTools(@Param("userId") long userId, @Param("server") String server);

    /**
     * 删除<strong>某个用户</strong>对某个服务的全部持久化授权：所有工具 + 服务级默认行
     * （{@code tool_name=''}）。
     *
     * <p>必须带 {@code user_id}：不同用户可以有同名服务，只按服务名删会把别人的策略一起删掉。
     * 只按 {@code (user_id, server_name)} 删，不会波及其他服务、其他用户。
     */
    @Delete("DELETE FROM mcp_user_policy WHERE user_id = #{userId} AND server_name = #{server}")
    int deleteByUserAndServer(@Param("userId") long userId, @Param("server") String server);
}

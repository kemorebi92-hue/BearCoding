package com.example.jingjingai2.mapper;

import com.example.jingjingai2.entity.McpServerCredential;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户级 MCP 凭证。读写条件必须同时带 user_id 和 server_config_id。 */
@Mapper
public interface McpServerCredentialMapper {

    @Insert("INSERT INTO mcp_server_credential(user_id, server_config_id, ciphertext) " +
            "VALUES(#{userId}, #{serverConfigId}, #{ciphertext})")
    int insert(McpServerCredential row);

    @Update("UPDATE mcp_server_credential SET ciphertext=#{ciphertext} " +
            "WHERE user_id=#{userId} AND server_config_id=#{serverConfigId}")
    int updateCiphertext(@Param("userId") long userId,
                         @Param("serverConfigId") long serverConfigId,
                         @Param("ciphertext") String ciphertext);

    @Select("SELECT id, user_id, server_config_id, ciphertext FROM mcp_server_credential " +
            "WHERE user_id=#{userId} AND server_config_id=#{serverConfigId}")
    McpServerCredential find(@Param("userId") long userId, @Param("serverConfigId") long serverConfigId);

    @Select("SELECT COUNT(*) FROM mcp_server_credential " +
            "WHERE user_id=#{userId} AND server_config_id=#{serverConfigId}")
    int count(@Param("userId") long userId, @Param("serverConfigId") long serverConfigId);

    @Delete("DELETE FROM mcp_server_credential WHERE user_id=#{userId} AND server_config_id=#{serverConfigId}")
    int delete(@Param("userId") long userId, @Param("serverConfigId") long serverConfigId);
}

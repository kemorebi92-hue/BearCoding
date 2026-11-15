package com.example.jingjingai2.mapper;

import com.example.jingjingai2.entity.UserMemory;
import com.example.jingjingai2.mapper.sql.UserMemorySqlProvider;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户记忆表 Mapper（MyBatis 注解式，风格对齐 {@link ConversationMapper}）。
 *
 * 同一用户 + 同类目 + 同 key 覆盖：用 ON DUPLICATE KEY UPDATE，和 conversation.touch 同一套路。
 */
@Mapper
public interface UserMemoryMapper {

    @InsertProvider(type = UserMemorySqlProvider.class, method = "upsert")
    int upsert(@Param("userId") Long userId,
               @Param("category") String category,
               @Param("memKey") String memKey,
               @Param("content") String content,
               @Param("source") String source,
               @Param("conversationId") String conversationId);

    @Select("SELECT id, user_id, category, mem_key, content, source, conversation_id, " +
            "       created_at, updated_at " +
            "FROM user_memory WHERE user_id = #{userId} " +
            "ORDER BY category ASC, updated_at DESC")
    List<UserMemory> listByUser(@Param("userId") Long userId);

    @Select("SELECT id, user_id, category, mem_key, content, source, conversation_id, " +
            "       created_at, updated_at " +
            "FROM user_memory WHERE user_id = #{userId} AND category = #{category} " +
            "AND mem_key = #{memKey} LIMIT 1")
    UserMemory getOne(@Param("userId") Long userId,
                      @Param("category") String category,
                      @Param("memKey") String memKey);

    @Delete("DELETE FROM user_memory WHERE user_id = #{userId} AND category = #{category} " +
            "AND mem_key = #{memKey}")
    int deleteOne(@Param("userId") Long userId,
                  @Param("category") String category,
                  @Param("memKey") String memKey);

    @Delete("DELETE FROM user_memory WHERE user_id = #{userId} AND category = #{category}")
    int deleteByCategory(@Param("userId") Long userId,
                         @Param("category") String category);

    @Select("SELECT COUNT(*) FROM user_memory WHERE user_id = #{userId}")
    int countByUser(@Param("userId") Long userId);
}

package com.example.jingjingai2.mapper;

import com.example.jingjingai2.entity.ModelConfig;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用户模型配置表 Mapper（MyBatis 注解式）。
 */
@Mapper
public interface ModelConfigMapper {

    String SELECT_COLUMNS = "id, user_id AS userId, provider, base_url AS baseUrl, " +
            "api_key AS apiKey, model_name AS modelName, context_window_tokens AS contextWindowTokens, " +
            "default_model AS defaultModel, " +
            "created_at AS createdAt";

    @Insert("INSERT INTO user_model_config(user_id, provider, base_url, api_key, model_name, context_window_tokens, default_model) " +
            "VALUES(#{userId}, #{provider}, #{baseUrl}, #{apiKey}, #{modelName}, #{contextWindowTokens}, #{defaultModel})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ModelConfig cfg);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_model_config WHERE user_id = #{userId} " +
            "ORDER BY default_model DESC, id ASC")
    List<ModelConfig> findByUserId(Long userId);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_model_config " +
            "WHERE id = #{id} AND user_id = #{userId}")
    ModelConfig findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT " + SELECT_COLUMNS + " FROM user_model_config " +
            "WHERE user_id = #{userId} AND default_model = 1 LIMIT 1")
    ModelConfig findDefault(Long userId);

    @Update("UPDATE user_model_config SET provider = #{provider}, base_url = #{baseUrl}, " +
            "api_key = #{apiKey}, model_name = #{modelName}, context_window_tokens = #{contextWindowTokens} " +
            "WHERE id = #{id} AND user_id = #{userId}")
    void update(ModelConfig cfg);

    @Update("UPDATE user_model_config SET default_model = 0 WHERE user_id = #{userId}")
    void clearDefaults(Long userId);

    @Update("UPDATE user_model_config SET default_model = 1 WHERE id = #{id} AND user_id = #{userId}")
    void setDefault(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("DELETE FROM user_model_config WHERE id = #{id} AND user_id = #{userId}")
    void delete(@Param("id") Long id, @Param("userId") Long userId);
}

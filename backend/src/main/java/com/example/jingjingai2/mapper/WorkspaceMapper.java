package com.example.jingjingai2.mapper;

import com.example.jingjingai2.entity.Workspace;
import com.example.jingjingai2.mapper.sql.WorkspaceSqlProvider;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 工作区表 Mapper。默认工作区不建行，只存绑定了真实目录的那些。
 */
@Mapper
public interface WorkspaceMapper {

    @Insert("INSERT INTO workspace(user_id, name, bound_path) " +
            "VALUES(#{userId}, #{name}, #{boundPath})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Workspace workspace);

    @Select("SELECT id, user_id, name, bound_path, created_at, updated_at FROM workspace " +
            "WHERE id = #{id}")
    Workspace selectById(@Param("id") Long id);

    @Select("SELECT id, user_id, name, bound_path, created_at, updated_at FROM workspace " +
            "WHERE user_id = #{userId} AND id = #{id}")
    Workspace selectByUserAndId(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT id, user_id, name, bound_path, created_at, updated_at FROM workspace " +
            "WHERE user_id = #{userId} AND bound_path = #{boundPath} LIMIT 1")
    Workspace selectByUserAndPath(@Param("userId") Long userId, @Param("boundPath") String boundPath);

    @Select("SELECT id, user_id, name, bound_path, created_at, updated_at FROM workspace " +
            "WHERE user_id = #{userId} ORDER BY updated_at DESC, id DESC")
    List<Workspace> listByUser(@Param("userId") Long userId);

    @Update("UPDATE workspace SET name = #{name}, updated_at = CURRENT_TIMESTAMP " +
            "WHERE user_id = #{userId} AND id = #{id}")
    int updateName(@Param("userId") Long userId, @Param("id") Long id, @Param("name") String name);

    @Delete("DELETE FROM workspace WHERE user_id = #{userId} AND id = #{id}")
    int deleteByUserAndId(@Param("userId") Long userId, @Param("id") Long id);

    @SelectProvider(type = WorkspaceSqlProvider.class, method = "countWorkspaceTable")
    int countWorkspaceTable();
}

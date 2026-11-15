package com.example.jingjingai2.mapper;

import com.example.jingjingai2.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper（MyBatis 注解式，替代原 UserJdbcRepository）。
 * @Mapper 会被 MyBatis Spring Boot 自动扫描并注册为 Spring Bean。
 */
@Mapper
public interface UserMapper {

    /** 插入用户，自增主键回填到 user.id */
    @Insert("INSERT INTO users(username, password) VALUES(#{username}, #{password})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(User user);

    /** 按用户名查 id 与密码哈希（不查明文） */
    @Select("SELECT id, password FROM users WHERE username = #{username} LIMIT 1")
    User findByUsername(String username);

    /** 用户名是否存在（COUNT 返回 Integer，>0 即存在） */
    @Select("SELECT COUNT(1) FROM users WHERE username = #{username}")
    Integer countByUsername(String username);
}

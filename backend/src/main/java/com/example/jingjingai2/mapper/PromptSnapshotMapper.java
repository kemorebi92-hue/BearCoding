package com.example.jingjingai2.mapper;

import com.example.jingjingai2.mapper.sql.PromptSnapshotSqlProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code prompt_snapshot} 表：系统提示和工具表的全文，按 sha256 去重。
 * {@code INSERT IGNORE} 保证同一个哈希只存第一次见到的那一份。
 */
@Mapper
public interface PromptSnapshotMapper {

    @InsertProvider(type = PromptSnapshotSqlProvider.class, method = "insertIgnore")
    void insertIgnore(@Param("sha256") String sha256,
                      @Param("kind") String kind,
                      @Param("content") String content);
}

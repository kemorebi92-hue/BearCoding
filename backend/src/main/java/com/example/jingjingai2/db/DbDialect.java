package com.example.jingjingai2.db;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 当前数据源的数据库类型。
 *
 * <p>项目只有一个 DataSource，一次进程只可能连一种库，所以用静态标志即可。
 * MyBatis 的注解 Mapper 通过 {@code *SqlProvider} 读它选择 SQL；
 * Mapper 方法签名保持不变，测试里的内存假 Mapper 不受影响。
 *
 * <p>识别方式：{@link java.sql.DatabaseMetaData#getDatabaseProductName()}。
 * 识别失败直接抛异常，不猜、不默认成 MySQL 之外的任何方言。
 */
@Component
public class DbDialect {

    private static volatile boolean sqlite;

    @Autowired(required = false)
    void init(DataSource dataSource) {
        sqlite = detect(dataSource);
    }

    public static boolean isSqlite() {
        return sqlite;
    }

    public static boolean isMySql() {
        return !sqlite;
    }

    /** 单测用：显式指定方言。传 null 恢复为「非 SQLite」。 */
    public static void overrideForTest(Boolean sqliteValue) {
        sqlite = sqliteValue != null && sqliteValue;
    }

    static boolean detect(DataSource dataSource) {
        if (dataSource == null) return false;
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("sqlite");
        } catch (SQLException e) {
            throw new IllegalStateException("无法识别数据库类型（DatabaseMetaData 读取失败）", e);
        }
    }
}

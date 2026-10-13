package com.scb.monitoring.config.db;

import java.sql.Connection;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.stereotype.Component;

/**
 * MyBatis : 매퍼 SQL 을 지금 주 DB 에 맞게 바꿈 ({@link DbDialect#rewrite}). MySQL · MariaDB · H2 · Oracle 은 그대로.
 * (mybatis-spring-boot 가 Interceptor 빈을 자동 등록)
 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class DialectSqlInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        DbKind kind = DbDialect.current();
        if (kind == DbKind.SYBASE) {
            BoundSql bound = ((StatementHandler) invocation.getTarget()).getBoundSql();
            String sql = DbDialect.rewrite(bound.getSql(), kind);
            if (!sql.equals(bound.getSql())) {
                MetaObject mo = SystemMetaObject.forObject(bound);
                mo.setValue("sql", sql);
            }
        }
        return invocation.proceed();
    }
}

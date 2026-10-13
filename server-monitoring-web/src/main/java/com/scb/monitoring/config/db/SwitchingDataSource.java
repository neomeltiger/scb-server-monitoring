package com.scb.monitoring.config.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;

import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * 주 DataSource (MyBatis · 트랜잭션 · JdbcTemplate) : 지금 주 DB 풀로 연결을 넘겨줌.
 * <ul>
 *   <li>연결을 못 얻으면 {@link DbManager#failover} 로 다음 순위 DB 로 바꾼 뒤 한 번 더 시도</li>
 *   <li>쓰는 중에 연결이 끊긴 오류(SQLSTATE 08xxx 등)가 나면 그 요청은 실패하고, 바로 뒤에서 교체 확인 ({@link DbManager#failoverAsync})
 *       - 다음 요청부터 바뀐 DB 사용</li>
 * </ul>
 */
public class SwitchingDataSource extends AbstractDataSource {

    private final DbManager manager;

    SwitchingDataSource(DbManager manager) {
        this.manager = manager;
    }

    @Override
    public Connection getConnection() throws SQLException {
        DbManager.Active a = manager.active();
        Connection c;
        try {
            c = a.pool.getConnection();
        } catch (SQLException e) {
            if (!connectionFailure(e)) throw e;
            DbManager.Active next = manager.failover(a, NamedJdbc.rootMessage(e));
            if (next == a) throw e;
            a = next;
            c = a.pool.getConnection();
        }
        return watch(c, Connection.class, a);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    /** Connection · Statement 호출에서 연결 끊김 오류가 나면 교체 확인을 시작 (결과·동작은 그대로) */
    @SuppressWarnings("unchecked")
    private <T> T watch(T target, Class<T> type, DbManager.Active a) {
        InvocationHandler h = (proxy, method, args) -> {
            try {
                Object r = method.invoke(target, args);
                Class<?> rt = method.getReturnType();
                if (r != null && (rt == Statement.class || rt == PreparedStatement.class || rt == CallableStatement.class)) {
                    return watch(r, (Class<Object>) rt, a);
                }
                return r;
            } catch (InvocationTargetException e) {
                Throwable t = e.getCause();
                if (t instanceof SQLException && connectionFailure((SQLException) t)) manager.failoverAsync(a, NamedJdbc.rootMessage(t));
                throw t;
            }
        };
        return (T) Proxy.newProxyInstance(SwitchingDataSource.class.getClassLoader(), new Class<?>[] {type}, h);
    }

    /** 연결 자체의 문제 (SQLSTATE 08xxx, 풀 시간 초과, 닫힌 연결·DB) */
    static boolean connectionFailure(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException || t instanceof SQLNonTransientConnectionException) return true;
            if (t instanceof SQLException) {
                String state = ((SQLException) t).getSQLState();
                if (state != null && state.startsWith("08")) return true;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("has been closed") || m.contains("is already closed") || m.contains("Connection refused")
                    || m.contains("Communications link failure") || m.contains("Closed Connection") || m.contains("I/O error"))) return true;
        }
        return false;
    }

    public DbManager getManager() { return manager; }
}

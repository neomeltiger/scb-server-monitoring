package com.scb.monitoring.config.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB 별 SQL 차이 처리.
 * <ul>
 *   <li>지금 주 DB 종류 : {@link #current()} - 매퍼 XML 에서 {@code @com.scb.monitoring.config.db.DbDialect@sybase()} 로 분기</li>
 *   <li>매퍼 SQL 실행 직전 바꿈 : {@link #rewrite} (Sybase : CURRENT_TIMESTAMP → getdate()) - {@link DialectSqlInterceptor}</li>
 *   <li>schema-ansi.sql → Oracle / Sybase DDL : {@link #ddl}</li>
 *   <li>data.sql / users.sql → Oracle / Sybase 문장 : {@link #data} (여러 행 INSERT 나눔, Sybase IDENTITY_INSERT)</li>
 * </ul>
 * MySQL / MariaDB 는 schema-mysql.sql, H2 는 schema-ansi.sql 을 그대로 씀.
 */
public final class DbDialect {

    private static volatile DbKind current = DbKind.H2;

    private static final Pattern CURRENT_TS = Pattern.compile("(?i)\\bCURRENT_TIMESTAMP\\b");
    private static final Pattern TS_LITERAL = Pattern.compile("(?i)\\bTIMESTAMP\\s+('[^']*')");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)\\s*$");
    private static final Pattern IDENTITY = Pattern.compile("(?i)\\bGENERATED\\s+BY\\s+DEFAULT\\s+AS\\s+IDENTITY(\\s*\\([^)]*\\))?");
    private static final Pattern VARCHAR = Pattern.compile("(?i)\\bVARCHAR\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern INSERT = Pattern.compile("(?is)^INSERT\\s+INTO\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*VALUES\\s*(.*)$");

    /** Sybase VARCHAR 길이 : 한글(UTF-8 3바이트)도 들어가도록 3배, 2K 페이지 서버 한도 아래로 */
    static final int SYBASE_VARCHAR_MAX = 1900;

    private DbDialect() {
    }

    public static DbKind current() { return current; }

    static void setCurrent(DbKind kind) { current = kind; }

    /** 매퍼 XML OGNL : {@code <when test="@com.scb.monitoring.config.db.DbDialect@sybase()">} */
    public static boolean sybase() { return current == DbKind.SYBASE; }

    public static boolean oracle() { return current == DbKind.ORACLE; }

    // ---------------------------------------------------------------- 매퍼 SQL

    /** 매퍼 SQL 을 그 DB 에 맞게 (바꿀 것이 없으면 그대로) */
    public static String rewrite(String sql, DbKind kind) {
        if (kind == DbKind.SYBASE && sql.toUpperCase(Locale.ROOT).contains("CURRENT_TIMESTAMP")) {
            return CURRENT_TS.matcher(sql).replaceAll("getdate()");
        }
        return sql;
    }

    // ---------------------------------------------------------------- 스크립트

    /** schema-ansi.sql 의 테이블별 IDENTITY 컬럼 (소문자) */
    public static Map<String, String> identityColumns(String ansiSchema) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String st : split(ansiSchema)) {
            Matcher t = CREATE_TABLE.matcher(st);
            if (!t.matches()) continue;
            for (String item : splitTopLevel(t.group(2))) {
                if (IDENTITY.matcher(item).find()) m.put(t.group(1).toLowerCase(Locale.ROOT), firstWord(item).toLowerCase(Locale.ROOT));
            }
        }
        return m;
    }

    /** schema-ansi.sql 의 CREATE TABLE 순서 (부모 테이블이 먼저) */
    public static List<String> tableOrder(String ansiSchema) {
        List<String> out = new ArrayList<>();
        for (String st : split(ansiSchema)) {
            Matcher t = CREATE_TABLE.matcher(st);
            if (t.matches()) out.add(t.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** schema-ansi.sql → 대상 DB DDL 문장 (DROP 은 뺌 - 빈 DB 에만 실행) */
    public static List<String> ddl(String ansiSchema, DbKind kind) {
        List<String> out = new ArrayList<>();
        for (String st : split(ansiSchema)) {
            if (st.toUpperCase(Locale.ROOT).startsWith("DROP ")) {
                if (kind == DbKind.H2) out.add(st);
                continue;
            }
            Matcher t = CREATE_TABLE.matcher(st);
            if (t.matches() && kind.mysqlFamily()) {
                out.add(mysqlTable(st));
                continue;
            }
            if (!t.matches() || kind == DbKind.H2 || kind.mysqlFamily()) {
                out.add(st);
                continue;
            }
            List<String> items = new ArrayList<>();
            for (String item : splitTopLevel(t.group(2))) {
                items.add(kind == DbKind.ORACLE ? oracleItem(item) : sybaseItem(item));
            }
            out.add("CREATE TABLE " + t.group(1) + " (\n    " + String.join(",\n    ", items) + "\n)");
        }
        return out;
    }

    /** MySQL / MariaDB : 자동 증가 컬럼과 CLOB 만 바꿈 (보완 생성용 - 빈 DB 는 schema-mysql.sql) */
    static String mysqlTable(String st) {
        String s = IDENTITY.matcher(st).replaceAll("AUTO_INCREMENT");
        return s.replaceAll("(?i)\\bCLOB\\b", "MEDIUMTEXT");
    }

    /** 컬럼 하나 (또는 제약조건) - Oracle */
    static String oracleItem(String item) {
        if (constraint(item)) return item;
        String s = item;
        s = s.replaceAll("(?i)\\bBIGINT\\b", "NUMBER(19)");
        s = s.replaceAll("(?i)\\bINT\\b", "NUMBER(10)");
        s = VARCHAR.matcher(s).replaceAll("VARCHAR2($1 CHAR)");
        return s;
    }

    /** 컬럼 하나 (또는 제약조건) - Sybase ASE (NULL 을 적지 않으면 NOT NULL 이 되므로 NULL 을 명시) */
    static String sybaseItem(String item) {
        if (constraint(item)) return item;
        String s = item;
        boolean identity = IDENTITY.matcher(s).find();
        s = IDENTITY.matcher(s).replaceAll("IDENTITY");
        s = s.replaceAll("(?i)\\bTIMESTAMP\\b", "DATETIME");
        s = s.replaceAll("(?i)\\bCLOB\\b", "TEXT");
        s = CURRENT_TS.matcher(s).replaceAll("getdate()");
        Matcher v = VARCHAR.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (v.find()) v.appendReplacement(sb, "VARCHAR(" + Math.min(Integer.parseInt(v.group(1)) * 3, SYBASE_VARCHAR_MAX) + ")");
        v.appendTail(sb);
        s = sb.toString();
        if (!identity && !s.toUpperCase(Locale.ROOT).contains("NOT NULL")) s = s + " NULL";
        return s;
    }

    private static boolean constraint(String item) {
        String w = firstWord(item).toUpperCase(Locale.ROOT);
        return w.equals("PRIMARY") || w.equals("CONSTRAINT") || w.equals("UNIQUE") || w.equals("FOREIGN") || w.equals("CHECK");
    }

    /**
     * data.sql / users.sql → 대상 DB 문장.
     * Oracle · Sybase : 여러 행 INSERT 를 한 행씩, Sybase : TIMESTAMP '...' → '...', CURRENT_TIMESTAMP → getdate(),
     * IDENTITY 컬럼에 값을 넣는 INSERT 는 SET IDENTITY_INSERT 로 감쌈.
     */
    public static List<String> data(String script, DbKind kind, Map<String, String> identities) {
        List<String> out = new ArrayList<>();
        for (String st : split(script)) {
            if (kind != DbKind.ORACLE && kind != DbKind.SYBASE) {
                out.add(st);
                continue;
            }
            Matcher ins = INSERT.matcher(st);
            if (!ins.matches()) {
                out.add(kind == DbKind.SYBASE ? CURRENT_TS.matcher(st).replaceAll("getdate()") : st);
                continue;
            }
            String table = ins.group(1);
            String cols = ins.group(2).trim();
            boolean idInsert = false;
            String idCol = identities.get(table.toLowerCase(Locale.ROOT));
            if (kind == DbKind.SYBASE && idCol != null) {
                for (String c : cols.split(",")) if (c.trim().equalsIgnoreCase(idCol)) idInsert = true;
            }
            if (idInsert) out.add("SET IDENTITY_INSERT " + table + " ON");
            for (String row : splitTopLevel(ins.group(3))) {
                String r = row.trim();
                if (kind == DbKind.SYBASE) {
                    r = TS_LITERAL.matcher(r).replaceAll("$1");
                    r = CURRENT_TS.matcher(r).replaceAll("getdate()");
                }
                out.add("INSERT INTO " + table + " (" + cols + ") VALUES " + r);
            }
            if (idInsert) out.add("SET IDENTITY_INSERT " + table + " OFF");
        }
        return out;
    }

    // ---------------------------------------------------------------- 나누기

    /** ; 로 문장 나누기 (따옴표 안 무시, -- 주석 제거, 빈 문장 제외) */
    public static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (!quote && ch == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') i++;
                cur.append('\n');
                continue;
            }
            if (ch == '\'') quote = !quote;
            if (!quote && ch == ';') {
                add(out, cur);
                continue;
            }
            cur.append(ch == '\r' ? '\n' : ch);
        }
        add(out, cur);
        return out;
    }

    private static void add(List<String> out, StringBuilder cur) {
        String s = cur.toString().trim().replaceAll("\n\\s*\n", "\n");
        if (!s.isEmpty()) out.add(s);
        cur.setLength(0);
    }

    /** 괄호 깊이 0 의 쉼표로 나누기 (따옴표 안 무시) - "(a), (b)" 또는 CREATE TABLE 본문 */
    static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean quote = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'') quote = !quote;
            if (!quote) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(ch);
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static String firstWord(String s) {
        String t = s.trim();
        int i = 0;
        while (i < t.length() && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '_')) i++;
        return t.substring(0, i);
    }
}

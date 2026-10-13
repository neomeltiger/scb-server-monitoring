package com.scb.monitoring.dbsetup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.scb.monitoring.config.db.DbDialect;
import com.scb.monitoring.config.db.DbKind;
import com.scb.monitoring.config.db.DbSchema;
import com.scb.monitoring.menu.service.MenuService;

/**
 * 프로젝트에 저장된 신규 DB 구성 스크립트(src/main/resources/db/setup)가 지금 스키마 · 기초 데이터와 같은지.
 * 스키마 · data.sql · 기본 메뉴를 바꾸면 : mvn test -Dtest=DbSetupScriptsTest -Dscb.setup.write=true 로 파일을 다시 씀
 */
class DbSetupScriptsTest {

    private static final File DIR = new File("src/main/resources/db/setup");

    private static void check(String name, String expected) throws IOException {
        File f = new File(DIR, name);
        if (Boolean.getBoolean("scb.setup.write")) {
            DIR.mkdirs();
            Files.write(f.toPath(), expected.getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(f.isFile(), name + " 없음 - -Dscb.setup.write=true 로 만드세요");
        String actual = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(expected, actual, name + " 가 스키마와 다릅니다 - -Dscb.setup.write=true 로 다시 쓰세요");
    }

    @Test
    void ddlFilesMatchSchema() throws IOException {
        for (DbKind k : new DbKind[] {DbKind.ORACLE, DbKind.SYBASE, DbKind.MYSQL, DbKind.H2}) {
            String ddl = DbSetupScripts.generateDdl(k);
            check(DbSetupScripts.ddlFile(k).substring(DbSetupScripts.DIR.length()), ddl);
            String u = ddl.toUpperCase(Locale.ROOT);
            assertFalse(u.contains("DROP TABLE"), k + " DDL 에 DROP 없음");
            assertFalse(u.contains("CREATE DATABASE"), k + " DDL 에 CREATE DATABASE 없음");
            int tables = 0;
            for (String st : DbDialect.split(ddl)) if (st.toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")) tables++;
            assertEquals(DbDialect.tableOrder(DbSchema.resource(DbSchema.ANSI)).size(), tables, k + " 테이블 수");
        }
        assertEquals(DbSetupScripts.ddlFile(DbKind.MYSQL), DbSetupScripts.ddlFile(DbKind.MARIADB), "MariaDB 는 MySQL DDL");
    }

    @Test
    void baseDataHasNoHistory() throws IOException {
        String data = DbSetupScripts.generateBaseData(MenuService.DEFAULTS);
        check("base-data.sql", data);
        List<String> stmts = DbDialect.split(data);
        for (String st : stmts) {
            String t = DbSetupScripts.tableOf(st);
            if (!t.isEmpty()) assertTrue(DbSetupScripts.baseTables().contains(t), "기초 데이터가 아닌 테이블 : " + t);
            String low = st.toLowerCase(Locale.ROOT);
            assertFalse(low.contains("_hist") || low.contains("tb_alarm") || low.contains("tb_server_metric ") || low.contains("tb_agent"),
                    "이력 · 수집 데이터 제외 : " + st);
        }
        assertTrue(data.contains("INSERT INTO tb_user"));
        assertTrue(data.contains("INSERT INTO tb_menu"));
        assertTrue(data.contains("INSERT INTO tb_server ("));
        assertTrue(data.contains("INSERT INTO tb_retention"));
        // Oracle / Sybase 로 바꿔도 문장이 만들어짐
        assertTrue(DbDialect.data(data, DbKind.SYBASE, DbDialect.identityColumns(DbSchema.resource(DbSchema.ANSI))).size() >= stmts.size());
        assertTrue(DbDialect.data(data, DbKind.ORACLE, DbDialect.identityColumns(DbSchema.resource(DbSchema.ANSI))).size() >= stmts.size());
    }
}

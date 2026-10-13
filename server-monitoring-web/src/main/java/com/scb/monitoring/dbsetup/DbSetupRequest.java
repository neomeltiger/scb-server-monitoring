package com.scb.monitoring.dbsetup;

/**
 * DB 초기 구성 요청 (화면 입력). 비밀번호는 요청할 때만 쓰고 저장 · 로그에 남기지 않음.
 * <pre>
 *  kind      oracle / sybase / mysql / mariadb / h2
 *  host, port, service   접속 위치 (service = Oracle 서비스 이름, 그 밖은 쓰지 않음)
 *  h2Dir     H2 파일 DB 폴더 (h2 만)
 *  url       직접 입력한 JDBC URL (선택 - 비우면 위 값으로 만듦, 스키마 없이 접속하는 주소)
 *  user / password       접속 계정 (스키마를 만들 권한 - MySQL root, Oracle DBA, Sybase sa ...)
 *  schema    만들 스키마 (MySQL · MariaDB database, Oracle 사용자, Sybase database, H2 파일 이름)
 *  appUser / appPassword 웹이 쓸 계정 (선택, Oracle 은 스키마 사용자의 비밀번호로 사용 - 스키마를 새로 만들 때 필수)
 * </pre>
 */
public class DbSetupRequest {

    private String kind;
    private String host;
    private Integer port;
    private String service;
    private String h2Dir;
    private String url;
    private String user;
    private String password;
    private String schema;
    private String appUser;
    private String appPassword;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getH2Dir() { return h2Dir; }
    public void setH2Dir(String h2Dir) { this.h2Dir = h2Dir; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getAppUser() { return appUser; }
    public void setAppUser(String appUser) { this.appUser = appUser; }
    public String getAppPassword() { return appPassword; }
    public void setAppPassword(String appPassword) { this.appPassword = appPassword; }
}

package com.scb.monitoring.srvreport.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.scb.monitoring.srvreport.domain.BasicRow;

/**
 * 웹 기본 리포트 : 서버 직접 점검(rpt|, 클라이언트 1.12~)을 못 하는 서버를 위해
 * 웹 DB 에 이미 수집된 자료(자원, 서비스·프로세스 상태, 서버 로그 수집본, 알람, 제어 이력)로
 * collect_report.sh 와 같은 형식(#SCBRPT / #SEC / #ITEM / #SUM / #WARN / #HL / #END)의 리포트를 만듦.
 * 로그 점검은 "서버 로그" 화면에 등록된 로그의 마지막 수집본만 대상 (서버의 SCB_LOG_ALLOW 안의 경로).
 */
public final class BasicReportBuilder {

    /** 분야 표시 코드 - 목록에서 웹 기본 리포트임을 알 수 있게 sections 끝에 붙임 */
    public static final String WEB_SECTION = "web";

    static final int SSH_WARN = 10;
    static final int DISK_WARN = 80;
    static final int DISK_CRIT = 90;
    static final int HIGH_PCT = 90;
    /** 항목별로 보여 줄 원문 줄 수 */
    static final int MAX_LINES = 20;

    private static final Pattern SSH_FAIL = Pattern.compile(
            "(?i)sshd.*(failed (password|keyboard-interactive|publickey|none)|invalid user|authentication failure|maximum authentication attempts)");
    private static final Pattern IP = Pattern.compile("(?:from|rhost=)\\s*(?:::ffff:)?(\\d{1,3}(?:\\.\\d{1,3}){3})");
    private static final Pattern SU_SUDO = Pattern.compile("(?i)(\\bsu(\\[\\d+\\])?:|\\bsudo(\\[\\d+\\])?:|'su |\\bsu: )");
    private static final Pattern SU_FAIL = Pattern.compile("(?i)(bad su|authentication failure|incorrect password|not in sudoers|su: .*fail|failed su)");
    private static final Pattern FIREWALL = Pattern.compile("(?i)(ufw block|ipmon\\[|\\bipf\\b.*\\bblock|iptables.*(drop|reject)|\\bDPT=|\\bSRC=.*\\bDST=)");
    private static final Pattern KERNEL = Pattern.compile("(?i)(panic|out of memory|oom-killer|killed process|file system full|no space left|"
            + "i/o error|scsi.*(error|retr)|\\bfault(ed)?\\b|\\boffline\\b|fmd.*(major|critical)|hardware error|transport failed)");
    private static final Pattern CRON = Pattern.compile("(?i)cron");
    private static final Pattern CRON_FAIL = Pattern.compile("(?i)(rc=[1-9]|error|fail|orphan|bad user|not allowed)");
    private static final Pattern MAIL = Pattern.compile("(?i)(postfix|sendmail|smtp|mailq)");
    private static final Pattern MAIL_BAD = Pattern.compile("(?i)(status=deferred|status=bounced|dsn=[45]\\.|deferred|bounced|stat=deferred)");
    private static final Pattern HTTP_STATUS = Pattern.compile("\"\\s+([45]\\d\\d)\\s");
    private static final Pattern PKG = Pattern.compile("(?i)(pkgadd|pkgrm|patchadd|patchrm|installed:|erased:|updated:|upgraded:|\\binstall |\\bremove |"
            + "useradd|userdel|usermod|groupadd|groupdel|passwd\\[|chpasswd|password changed)");
    private static final Pattern ACCOUNT = Pattern.compile("(?i)(useradd|userdel|usermod|groupadd|groupdel|passwd\\[|chpasswd|password changed)");

    private BasicReportBuilder() {
    }

    /** 리포트에 쓸 자료 */
    public static class Input {
        public String host;
        public String os;
        public String clientVer;
        public List<String> sections = Collections.emptyList();
        public BasicRow metric;
        public BasicRow peak;
        public List<BasicRow> services = Collections.emptyList();
        public List<BasicRow> processes = Collections.emptyList();
        public List<BasicRow> logs = Collections.emptyList();
        public List<BasicRow> alarms = Collections.emptyList();
        public List<BasicRow> cmds = Collections.emptyList();
        public Date now = new Date();
    }

    public static String build(Input in) {
        Out o = new Out();
        o.line("#SCBRPT|1|" + nz(in.host) + "|" + nz(in.os) + "|" + fmt(in.now) + "|" + String.join(",", in.sections));
        o.item("basic", "웹 기본 리포트", "웹 DB (이미 수집된 자료)");
        o.sum("클라이언트(" + (in.clientVer == null ? "-" : in.clientVer) + ")는 서버 직접 점검을 지원하지 않아, 웹에 이미 수집된 자료로 만든 리포트입니다.");
        o.sum("서버 직접 점검(로그 전체, sudo·su, 방화벽, 패키지·/etc 변경 등)은 svr_collect_client 1.12 이상으로 교체하면 됩니다.");
        o.sum("로그 항목은 서버 로그 화면에 등록된 로그의 마지막 수집본 " + in.logs.size() + "개를 검사합니다.");
        if (in.logs.isEmpty()) o.line("서버 로그 화면에서 /var/adm/messages 등 로그를 등록하면 로그 항목이 채워집니다.");
        for (BasicRow l : in.logs) {
            o.line("- " + nz(l.getItemNm()) + " " + nz(l.getItemPath()) + " : " + logState(l) + " (수집 " + fmt(l.getDt()) + ")");
        }
        for (String s : in.sections) {
            if ("sec".equals(s)) sec(o, in);
            else if ("res".equals(s)) res(o, in);
            else if ("svc".equals(s)) svc(o, in);
            else if ("chg".equals(s)) chg(o, in);
        }
        o.line("#END|0");
        return o.toString();
    }

    // ------------------------------------------------------------------ 보안
    private static void sec(Out o, Input in) {
        o.line("#SEC|sec|보안 및 접근 제어");

        o.item("ssh", "SSH 로그인 실패", logSources(in));
        List<String> fails = grep(in.logs, SSH_FAIL, null);
        Map<String, Integer> ips = new LinkedHashMap<>();
        for (String l : fails) {
            Matcher m = IP.matcher(l);
            if (m.find()) ips.merge(m.group(1), 1, Integer::sum);
        }
        o.sum("로그인 실패 " + fails.size() + "건" + (ips.isEmpty() ? "" : ", IP " + ips.size() + "개"));
        if (fails.size() >= SSH_WARN) o.warn("SSH 로그인 실패 " + fails.size() + "건 (기준 " + SSH_WARN + "건)");
        List<Map.Entry<String, Integer>> top = new ArrayList<>(ips.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < top.size() && i < 10; i++) o.line("  " + top.get(i).getValue() + "회  " + top.get(i).getKey());
        o.tail(fails);

        o.item("sudo", "sudo / su 사용", logSources(in));
        List<String> su = grep(in.logs, SU_SUDO, null);
        List<String> suFail = new ArrayList<>();
        for (String l : su) if (SU_FAIL.matcher(l).find()) suFail.add(l);
        o.sum("sudo / su 기록 " + su.size() + "건, 실패 " + suFail.size() + "건");
        if (!suFail.isEmpty()) o.warn("su / sudo 실패 " + suFail.size() + "건");
        o.tail(su);

        o.item("fw", "방화벽 차단", logSources(in));
        List<String> fw = grep(in.logs, FIREWALL, null);
        o.sum("차단 기록 " + fw.size() + "건");
        o.tail(fw);
    }

    // ------------------------------------------------------------------ 자원
    private static void res(Out o, Input in) {
        o.line("#SEC|res|시스템 자원");

        BasicRow m = in.metric;
        o.item("disk", "디스크 / CPU / 메모리 (최근 수집)", "에이전트 자원 보고" + (m == null ? "" : " " + fmt(m.getDt())));
        if (m == null) {
            o.sum("수집된 자원 정보가 없습니다.");
        } else {
            o.sum("CPU " + pct(m.getCpuPct()) + " · 메모리 " + pct(m.getMemPct()) + " · 디스크(가장 높은 파일시스템) " + pct(m.getDiskPct())
                    + " · 세션 " + (m.getSessionCnt() == null ? "-" : m.getSessionCnt()));
            int disk = num(m.getDiskPct());
            if (disk >= DISK_CRIT) o.warn("디스크 사용률 " + disk + "% (위험 " + DISK_CRIT + "% 이상)");
            else if (disk >= DISK_WARN) o.warn("디스크 사용률 " + disk + "% (경고 " + DISK_WARN + "% 이상)");
            if (num(m.getCpuPct()) >= HIGH_PCT) o.warn("CPU 사용률 " + pct(m.getCpuPct()));
            if (num(m.getMemPct()) >= HIGH_PCT) o.warn("메모리 사용률 " + pct(m.getMemPct()));
        }

        BasicRow p = in.peak;
        o.item("trend", "최근 24시간 최댓값", "자원 수집 이력");
        if (p == null || p.getCnt() == null || p.getCnt() == 0) {
            o.sum("최근 24시간 수집 이력이 없습니다.");
        } else {
            o.sum("수집 " + p.getCnt() + "회 · 최대 CPU " + pct(p.getCpuPct()) + " · 메모리 " + pct(p.getMemPct())
                    + " · 디스크 " + pct(p.getDiskPct()) + " · 세션 " + (p.getSessionCnt() == null ? "-" : p.getSessionCnt()));
        }

        o.item("kernel", "커널 메시지 (OOM, 패닉, 디스크·하드웨어 오류)", logSources(in));
        List<String> k = grep(in.logs, KERNEL, null);
        o.sum("오류성 커널 메시지 " + k.size() + "건");
        if (!k.isEmpty()) o.warn("커널·하드웨어 오류 메시지 " + k.size() + "건");
        o.tail(k);
    }

    // ------------------------------------------------------------------ 서비스
    private static void svc(Out o, Input in) {
        o.line("#SEC|svc|서비스 및 스케줄러");

        o.item("scb", "★ 웹이 관리하는 서비스 (프로세스 관리)", "웹 DB 서비스 상태 (마지막 보고)");
        List<String> stopped = new ArrayList<>();
        int running = 0;
        for (BasicRow s : in.services) {
            String st = nz(s.getStateCd());
            if ("RUNNING".equals(st)) running++;
            if ("STOPPED".equals(st)) stopped.add(s.getItemNm());
            o.hl(nz(s.getItemNm()) + " : " + svcState(st) + (s.getPid() == null ? "" : " (PID " + s.getPid() + ")")
                    + " · 계정 " + nz(s.getUserNm()) + " · 확인 " + fmt(s.getDt()));
        }
        if (in.services.isEmpty()) o.sum("웹에 등록된 서비스가 없습니다.");
        else o.sum("서비스 " + in.services.size() + "개 : 실행 " + running + " · 중지 " + stopped.size() + " · 알 수 없음 " + (in.services.size() - running - stopped.size()));
        if (!stopped.isEmpty()) o.warn("웹 관리 서비스 중지 " + stopped.size() + "개 : " + names(stopped));

        o.item("scbproc", "★ 웹에 등록된 프로세스 (구성도 주요 프로세스)", "웹 DB 프로세스 상태 (마지막 보고)");
        List<String> down = new ArrayList<>();
        for (BasicRow p : in.processes) {
            boolean up = "Y".equals(p.getStateCd());
            if (!up) down.add(p.getItemNm());
            o.hl(nz(p.getItemNm()) + " : " + (up ? "실행 중 (PID " + (p.getPid() == null ? "-" : p.getPid()) + ", CPU " + pct(p.getCpuPct())
                    + ", MEM " + pct(p.getMemPct()) + ")" : "실행 안 됨") + " · " + nz(p.getItemPath()) + " · 확인 " + fmt(p.getDt()));
        }
        if (in.processes.isEmpty()) o.sum("웹에 등록된 프로세스가 없습니다.");
        else o.sum("프로세스 " + in.processes.size() + "개 : 실행 " + (in.processes.size() - down.size()) + " · 중지 " + down.size());
        if (!down.isEmpty()) o.warn("등록 프로세스 실행 안 됨 " + down.size() + "개 : " + names(down));

        o.item("alarm", "장애 알람 (최근 7일)", "웹 알람 이력");
        int open = 0;
        for (BasicRow a : in.alarms) if (!"CLOSED".equals(a.getStateCd())) open++;
        o.sum("알람 " + in.alarms.size() + "건, 미해결 " + open + "건");
        if (open > 0) o.warn("미해결 알람 " + open + "건");
        for (int i = 0; i < in.alarms.size() && i < MAX_LINES; i++) {
            BasicRow a = in.alarms.get(i);
            o.line(fmt(a.getDt()) + "  [" + nz(a.getLevelCd()) + "] " + nz(a.getItemNm()) + " " + nz(a.getStateCd()) + "  " + nz(a.getMsg())
                    + (a.getEndDt() == null ? "" : "  (해소 " + fmt(a.getEndDt()) + ")"));
        }

        o.item("cron", "cron 작업 오류", logSources(in));
        List<String> cron = grep(in.logs, CRON, CRON_FAIL);
        o.sum("cron 오류 기록 " + cron.size() + "건");
        if (!cron.isEmpty()) o.warn("cron 오류 " + cron.size() + "건");
        o.tail(cron);

        o.item("web", "웹 서버 4xx / 5xx", logSources(in));
        Map<String, Integer> codes = new LinkedHashMap<>();
        List<String> err5 = new ArrayList<>();
        for (BasicRow l : in.logs) {
            if (!"OK".equals(l.getStateCd()) || l.getContent() == null) continue;
            for (String line : l.getContent().split("\n")) {
                Matcher m = HTTP_STATUS.matcher(line);
                if (!m.find()) continue;
                codes.merge(m.group(1), 1, Integer::sum);
                if (m.group(1).startsWith("5")) err5.add(nz(l.getItemNm()) + ": " + line);
            }
        }
        if (codes.isEmpty()) o.sum("수집된 로그에 웹 접근 기록이 없습니다.");
        else o.sum("상태 코드 " + codes);
        if (!err5.isEmpty()) o.warn("웹 서버 5xx 응답 " + err5.size() + "건");
        o.tail(err5);

        o.item("mail", "메일 지연·반송", logSources(in));
        List<String> mail = grep(in.logs, MAIL, MAIL_BAD);
        o.sum("지연·반송 기록 " + mail.size() + "건");
        o.tail(mail);
    }

    // ------------------------------------------------------------------ 변경
    private static void chg(Out o, Input in) {
        o.line("#SEC|chg|패키지 및 환경 변화");

        o.item("ctl", "웹에서 실행한 서비스 제어 (최근 7일)", "웹 제어 명령 이력");
        int failed = 0;
        for (BasicRow c : in.cmds) if ("FAILED".equals(c.getStateCd()) || "EXPIRED".equals(c.getStateCd())) failed++;
        o.sum("제어 명령 " + in.cmds.size() + "건, 실패·만료 " + failed + "건");
        if (failed > 0) o.warn("서비스 제어 실패·만료 " + failed + "건");
        for (int i = 0; i < in.cmds.size() && i < MAX_LINES; i++) {
            BasicRow c = in.cmds.get(i);
            o.line(fmt(c.getDt()) + "  " + nz(c.getUserNm()) + "  " + nz(c.getCmdCd()) + " " + nz(c.getItemNm()) + "  → " + nz(c.getStateCd())
                    + (c.getExitCd() == null ? "" : " (rc=" + c.getExitCd() + ")"));
        }

        o.item("pkg", "패키지·계정 변경 기록", logSources(in));
        List<String> pkg = grep(in.logs, PKG, null);
        int account = 0;
        for (String l : pkg) if (ACCOUNT.matcher(l).find()) account++;
        o.sum("변경 기록 " + pkg.size() + "건 (계정·비밀번호 " + account + "건). 패키지 설치 목록·/etc 변경 파일은 1.12 서버 직접 점검에서 확인합니다.");
        if (account > 0) o.warn("계정·비밀번호 변경 기록 " + account + "건");
        o.tail(pkg);
    }

    // ------------------------------------------------------------------ 공통

    /** 정상 수집된 로그에서 p (그리고 also 가 있으면 둘 다) 에 맞는 줄 - "로그이름: 줄" */
    static List<String> grep(List<BasicRow> logs, Pattern p, Pattern also) {
        List<String> out = new ArrayList<>();
        for (BasicRow l : logs) {
            if (!"OK".equals(l.getStateCd()) || l.getContent() == null) continue;
            for (String line : l.getContent().split("\n")) {
                String t = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (p.matcher(t).find() && (also == null || also.matcher(t).find())) out.add(nz(l.getItemNm()) + ": " + t);
            }
        }
        return out;
    }

    private static String logSources(Input in) {
        if (in.logs.isEmpty()) return "서버 로그 수집본 없음";
        List<String> n = new ArrayList<>();
        for (BasicRow l : in.logs) if ("OK".equals(l.getStateCd())) n.add(nz(l.getItemPath()));
        return n.isEmpty() ? "정상 수집된 서버 로그 없음" : String.join(", ", n);
    }

    private static String logState(BasicRow l) {
        String st = nz(l.getStateCd());
        if ("OK".equals(st)) return (l.getCnt() == null ? 0 : l.getCnt()) + "줄";
        if ("NOT_FOUND".equals(st)) return "파일 없음";
        if ("DENIED".equals(st)) return "허용 안 됨 (SCB_LOG_ALLOW)";
        return "오류";
    }

    private static String svcState(String st) {
        if ("RUNNING".equals(st)) return "실행 중";
        if ("STOPPED".equals(st)) return "중지";
        return "알 수 없음";
    }

    private static String names(List<String> n) {
        int max = 10;
        String s = String.join(", ", n.size() > max ? n.subList(0, max) : n);
        return n.size() > max ? s + " 외 " + (n.size() - max) + "개" : s;
    }

    private static int num(BigDecimal v) {
        return v == null ? 0 : v.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }

    private static String pct(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString() + "%";
    }

    private static String fmt(Date d) {
        return d == null ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d instanceof Timestamp ? new Date(d.getTime()) : d);
    }

    private static String nz(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }

    /** 줄 모음 - 리포트 표식 안의 값에는 줄바꿈이 들어가지 않게 */
    private static final class Out {
        private final StringBuilder sb = new StringBuilder();

        void line(String s) { sb.append(s).append('\n'); }
        void item(String code, String title, String source) { line("#ITEM|" + code + "|" + title + "|" + source); }
        void sum(String s) { line("#SUM|" + s); }
        void warn(String s) { line("#WARN|" + s); }
        void hl(String s) { line("#HL|" + s); }

        /** 마지막 MAX_LINES 줄 */
        void tail(List<String> lines) {
            int from = Math.max(0, lines.size() - MAX_LINES);
            if (from > 0) line("... 앞 " + from + "줄 생략");
            for (int i = from; i < lines.size(); i++) line(lines.get(i).length() > 400 ? lines.get(i).substring(0, 400) : lines.get(i));
        }

        @Override
        public String toString() { return sb.toString(); }
    }
}

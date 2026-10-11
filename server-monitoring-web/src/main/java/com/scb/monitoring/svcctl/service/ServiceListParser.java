package com.scb.monitoring.svcctl.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.scb.monitoring.svcctl.domain.ServiceItem;
import com.scb.monitoring.svcctl.dto.ImportResult;

/**
 * krcasmrx11_agent/etc/service.list 파서.
 * <pre>
 * # GROUP|LEVEL|SERVICE_NAME|ACCOUNT|SCRIPT(SCRIPT_HOME 기준 상대경로)|ARG|CMDS
 * 2|2|SCB_fs_app1|murex|SCB_fs_app1/mxg_fs.sh||start|stop|test|kill|clean
 * </pre>
 * 순번(list_no)은 주석을 제외한 줄 순서 = scb_main.sh 의 idx 번호와 같습니다.
 * 값이 올바르지 않은 줄은 건너뛰고 사유를 결과에 남깁니다. (명령 실행에 쓰이는 값이므로 엄격히 검사)
 */
public final class ServiceListParser {

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,50}$");
    static final Pattern ACCOUNT = Pattern.compile("^[A-Za-z_][A-Za-z0-9._-]{0,29}$");
    static final Pattern SCRIPT = Pattern.compile("^[A-Za-z0-9_./-]{1,200}$");
    static final Pattern ARG = Pattern.compile("^[A-Za-z0-9_.:-]{0,100}$");
    static final Set<String> SCRIPT_CMDS = new HashSet<>(java.util.Arrays.asList("start", "stop", "test", "check", "kill", "clean"));

    private ServiceListParser() { }

    public static List<ServiceItem> parse(String text, String scriptHome, Long serverId, ImportResult result) {
        List<ServiceItem> list = new ArrayList<>();
        Set<String> names = new HashSet<>();
        String home = scriptHome.endsWith("/") ? scriptHome.substring(0, scriptHome.length() - 1) : scriptHome;
        String[] lines = text.replace("\r", "").split("\n");
        int no = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", -1);
            int lineNo = i + 1;
            if (f.length < 7) { result.skip(lineNo + "행: 항목 수 부족 (GROUP|LEVEL|NAME|ACCOUNT|SCRIPT|ARG|CMDS)"); continue; }
            no++;   // scb_main.sh 와 같이 형식이 맞는 줄마다 번호 증가
            Integer group = toInt(f[0]);
            Integer level = toInt(f[1]);
            String name = f[2].trim();
            String account = f[3].trim();
            String script = f[4].trim();
            String arg = f[5].trim();
            StringBuilder cmds = new StringBuilder();
            boolean badCmd = false;
            for (int k = 6; k < f.length; k++) {
                String c = f[k].trim();
                if (c.isEmpty()) continue;
                if (!SCRIPT_CMDS.contains(c)) { badCmd = true; break; }
                if (cmds.length() > 0) cmds.append('|');
                cmds.append(c);
            }
            String err = null;
            if (group == null || level == null) err = "GROUP/LEVEL 은 숫자";
            else if (!NAME.matcher(name).matches()) err = "서비스명 형식 오류";
            else if (!ACCOUNT.matcher(account).matches()) err = "계정 형식 오류";
            else if (!SCRIPT.matcher(script).matches() || script.contains("..")) err = "스크립트 경로 형식 오류";
            else if (!ARG.matcher(arg).matches()) err = "ARG 형식 오류";
            else if (badCmd || cmds.length() == 0) err = "CMDS 는 start|stop|test|check|kill|clean 중에서";
            else if (!names.add(name)) err = "서비스명 중복 " + name;
            if (err != null) { result.skip(lineNo + "행: " + err); continue; }

            ServiceItem s = new ServiceItem();
            s.setServerId(serverId);
            s.setListNo(no);
            s.setGroupNo(group);
            s.setLevelNo(level);
            s.setSvcNm(name);
            s.setAccountNm(account);
            s.setScriptPath(script.startsWith("/") ? script : home + "/" + script);
            s.setScriptArg(arg.isEmpty() ? null : arg);
            s.setCmds(cmds.toString());
            list.add(s);
        }
        result.setTotal(list.size());
        return list;
    }

    private static Integer toInt(String v) {
        try {
            int n = Integer.parseInt(v.trim());
            return n >= 0 && n < 1000 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

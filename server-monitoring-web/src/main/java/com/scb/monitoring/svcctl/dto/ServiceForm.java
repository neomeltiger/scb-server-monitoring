package com.scb.monitoring.svcctl.dto;

import java.util.List;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 프로세스 관리 화면 : 서비스(service.list 1줄) 추가 / 수정.
 * 값 검사는 service.list 가져오기와 같은 규칙(ServiceListParser)으로 한 번 더 합니다.
 * SCRIPT 가 / 로 시작하지 않으면 scriptHome(SCB_SCRIPT_HOME) 기준 상대 경로.
 */
public class ServiceForm {

    @NotNull(message = "GROUP 을 입력하세요.")
    @Min(value = 0, message = "GROUP 은 0 ~ 999") @Max(value = 999, message = "GROUP 은 0 ~ 999")
    private Integer group;

    @NotNull(message = "LEVEL 을 입력하세요.")
    @Min(value = 0, message = "LEVEL 은 0 ~ 999") @Max(value = 999, message = "LEVEL 은 0 ~ 999")
    private Integer level;

    @NotBlank(message = "서비스명을 입력하세요.")
    @Size(max = 50, message = "서비스명은 50자 이내")
    private String name;

    @NotBlank(message = "실행 계정을 입력하세요.")
    @Size(max = 30, message = "계정은 30자 이내")
    private String account;

    @NotBlank(message = "제어 스크립트 경로를 입력하세요.")
    @Size(max = 200, message = "스크립트 경로는 200자 이내")
    private String script;

    @Size(max = 100, message = "ARG 는 100자 이내")
    private String arg;

    @NotEmpty(message = "지원 명령(CMDS)을 하나 이상 고르세요.")
    private List<String> cmds;

    private String scriptHome = "/shared/opt/pos";

    public Integer getGroup() { return group; }
    public void setGroup(Integer group) { this.group = group; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account == null ? null : account.trim(); }
    public String getScript() { return script; }
    public void setScript(String script) { this.script = script == null ? null : script.trim(); }
    public String getArg() { return arg; }
    public void setArg(String arg) { this.arg = arg == null ? null : arg.trim(); }
    public List<String> getCmds() { return cmds; }
    public void setCmds(List<String> cmds) { this.cmds = cmds; }
    public String getScriptHome() { return scriptHome; }
    public void setScriptHome(String scriptHome) { this.scriptHome = scriptHome == null || scriptHome.trim().isEmpty() ? "/shared/opt/pos" : scriptHome.trim(); }

    /** service.list 1줄 : GROUP|LEVEL|NAME|ACCOUNT|SCRIPT|ARG|CMDS... */
    public String toLine() {
        return group + "|" + level + "|" + name + "|" + account + "|" + script + "|" + (arg == null ? "" : arg) + "|"
                + String.join("|", cmds);
    }
}

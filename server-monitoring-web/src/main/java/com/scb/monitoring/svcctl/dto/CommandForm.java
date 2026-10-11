package com.scb.monitoring.svcctl.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/** 서비스 제어 요청 : 대상(all / group:N / level:N / 서비스명) + 명령 */
public class CommandForm {

    @NotBlank(message = "대상을 선택하세요.")
    @Pattern(regexp = "^(all|group:\\d{1,3}|level:\\d{1,3}|[A-Za-z0-9_.-]{1,50})$", message = "대상 형식이 올바르지 않습니다.")
    private String target;

    @NotBlank(message = "명령을 선택하세요.")
    @Pattern(regexp = "^(start|stop|restart|status|kill|clean)$", message = "지원하지 않는 명령입니다.")
    private String cmd;

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getCmd() { return cmd; }
    public void setCmd(String cmd) { this.cmd = cmd; }
}

package com.scb.monitoring.svcctl.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** service.list 가져오기 요청 (파일 내용 + 제어 스크립트 위치 SCB_SCRIPT_HOME) */
public class ImportForm {

    @NotBlank(message = "service.list 내용을 입력하세요.")
    @Size(max = 200000, message = "service.list 내용이 너무 큽니다.")
    private String text;

    @Pattern(regexp = "^/[A-Za-z0-9_./-]{0,200}$", message = "스크립트 위치는 / 로 시작하는 경로여야 합니다. (예: /shared/opt/pos)")
    private String scriptHome = "/shared/opt/pos";

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getScriptHome() { return scriptHome; }
    public void setScriptHome(String scriptHome) { this.scriptHome = scriptHome; }
}

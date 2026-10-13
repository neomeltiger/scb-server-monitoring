package com.scb.monitoring.serverlog.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 수집 대상 로그 등록 / 수정.
 * 경로는 절대 경로, 영문·숫자·_ . / - 만 허용 (".." 금지는 서비스에서 검사).
 * 서버의 svr_collect_client 도 etc/client.env 의 SCB_LOG_ALLOW 목록에 있는 경로만 읽습니다.
 */
public class LogSourceForm {

    @NotBlank(message = "로그 이름을 입력하세요.")
    @Size(max = 50, message = "로그 이름은 50자 이내로 입력하세요.")
    private String name;

    @NotBlank(message = "로그 파일 경로를 입력하세요.")
    @Size(max = 255, message = "로그 파일 경로는 255자 이내로 입력하세요.")
    @Pattern(regexp = "^/[A-Za-z0-9_./-]+$", message = "경로는 / 로 시작하는 절대 경로이며 영문, 숫자, _ . / - 만 쓸 수 있습니다.")
    private String path;

    @Min(value = 10, message = "가져올 줄 수는 10 ~ 1000 입니다.")
    @Max(value = 1000, message = "가져올 줄 수는 10 ~ 1000 입니다.")
    private int tailLines = 200;

    private boolean use = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path == null ? null : path.trim(); }
    public int getTailLines() { return tailLines; }
    public void setTailLines(int tailLines) { this.tailLines = tailLines; }
    public boolean isUse() { return use; }
    public void setUse(boolean use) { this.use = use; }
}

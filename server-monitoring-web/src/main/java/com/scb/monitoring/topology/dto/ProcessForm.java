package com.scb.monitoring.topology.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 프로세스 등록 / 수정 요청 */
public class ProcessForm {

    @NotBlank(message = "프로세스명을 입력하세요.")
    @Size(max = 100, message = "프로세스명은 100자 이내로 입력하세요.")
    private String name;

    @NotBlank(message = "실행 파일 경로를 입력하세요.")
    @Size(max = 255, message = "실행 파일 경로는 255자 이내로 입력하세요.")
    private String path;

    private boolean running = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }
}

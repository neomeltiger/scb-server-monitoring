package com.scb.monitoring.topology.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** 서버 추가 / 수정 요청 (네트워크 구성도 화면의 입력 폼) */
public class ServerForm {

    @NotBlank(message = "시스템명을 입력하세요.")
    @Size(max = 100, message = "시스템명은 100자 이내로 입력하세요.")
    private String systemName;

    @NotBlank(message = "FQDN 을 입력하세요.")
    @Size(max = 255, message = "FQDN 은 255자 이내로 입력하세요.")
    @Pattern(regexp = "^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
             message = "FQDN 형식이 올바르지 않습니다. (예: hostname.domain.com)")
    private String fqdn;

    @Size(max = 30, message = "OS 종류는 30자 이내로 입력하세요.")
    private String os;

    @NotBlank(message = "IP 주소를 입력하세요.")
    @Pattern(regexp = "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$",
             message = "IP 주소 형식이 올바르지 않습니다. (예: 10.0.13.217)")
    private String ip;

    @NotNull(message = "설치장소(스위치)를 선택하세요.")
    private Integer switchNodeId;

    @NotBlank(message = "시스템 구분을 선택하세요.")
    @Pattern(regexp = "^(Active|Standby|Down)$", message = "시스템 구분은 Active / Standby / Down 중 하나입니다.")
    private String status;

    public String getSystemName() { return systemName; }
    public void setSystemName(String systemName) { this.systemName = systemName; }
    public String getFqdn() { return fqdn; }
    public void setFqdn(String fqdn) { this.fqdn = fqdn; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public Integer getSwitchNodeId() { return switchNodeId; }
    public void setSwitchNodeId(Integer switchNodeId) { this.switchNodeId = switchNodeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

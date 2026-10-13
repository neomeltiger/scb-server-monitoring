package com.scb.monitoring.retention.domain;

import java.sql.Timestamp;

/** tb_retention : 분류별 최장 보관 일수 */
public class Retention {

    private String categoryCd;
    private int keepDays;
    private String updUser;
    private Timestamp updDt;

    public String getCategoryCd() { return categoryCd; }
    public void setCategoryCd(String categoryCd) { this.categoryCd = categoryCd; }
    public int getKeepDays() { return keepDays; }
    public void setKeepDays(int keepDays) { this.keepDays = keepDays; }
    public String getUpdUser() { return updUser; }
    public void setUpdUser(String updUser) { this.updUser = updUser; }
    public Timestamp getUpdDt() { return updDt; }
    public void setUpdDt(Timestamp updDt) { this.updDt = updDt; }
}

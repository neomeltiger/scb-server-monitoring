package com.scb.monitoring.retention.domain;

import java.sql.Timestamp;

/** 보관 대상 테이블 하나의 행 수 · 가장 오래된 일시 */
public class RetentionStat {

    private Long cnt;
    private Timestamp oldest;

    public Long getCnt() { return cnt; }
    public void setCnt(Long cnt) { this.cnt = cnt; }
    public Timestamp getOldest() { return oldest; }
    public void setOldest(Timestamp oldest) { this.oldest = oldest; }
}

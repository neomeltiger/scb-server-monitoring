package com.scb.monitoring.config.db;

import org.springframework.context.ApplicationEvent;

/** 주 DB 가 바뀜 (자동 교체 또는 관리자 전환) - 캐시를 가진 서비스가 다시 읽도록 */
public class DbSwitchedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final DbKind from;
    private final DbKind to;
    private final boolean automatic;

    public DbSwitchedEvent(Object source, DbKind from, DbKind to, boolean automatic) {
        super(source);
        this.from = from;
        this.to = to;
        this.automatic = automatic;
    }

    public DbKind getFrom() { return from; }
    public DbKind getTo() { return to; }
    public boolean isAutomatic() { return automatic; }
}

package com.scb.monitoring.agent.service;

import java.math.BigDecimal;

import com.scb.monitoring.agent.AgentProperties;

/**
 * 서버 부하 시 수집 주기 자동 연장.
 * <pre>
 *  CPU 또는 메모리 사용률이 app.agent.load-throttle-pct (기본 90%) 를 넘으면
 *    → 수집 주기 = max(사용자 설정, app.agent.load-throttle-min-sec (기본 60초))
 *  그 밖에는 사용자가 정한 수집 주기 그대로
 * </pre>
 * 판단 값은 에이전트가 마지막으로 보고한 서버 자원 (tb_server_metric). 데몬은 10초마다 웹을 확인하며
 * ctl|interval=N 을 받으므로 부하가 오르내리면 다음 확인 때 바로 바뀝니다. (사용자 설정 값은 DB 에 그대로 둠)
 */
public final class LoadThrottle {

    private LoadThrottle() {
    }

    /** CPU 또는 메모리가 기준을 넘었는지 */
    public static boolean overloaded(BigDecimal cpu, BigDecimal mem, AgentProperties p) {
        return over(cpu, p.getLoadThrottlePct()) || over(mem, p.getLoadThrottlePct());
    }

    /** 실제로 적용할 수집 주기 (초) */
    public static int effectiveInterval(int userSec, BigDecimal cpu, BigDecimal mem, AgentProperties p) {
        return overloaded(cpu, mem, p) ? Math.max(userSec, p.getLoadThrottleMinSec()) : userSec;
    }

    /** 화면 표시용 사유 (예 : "CPU 95%") - 기준 이하면 null */
    public static String reason(BigDecimal cpu, BigDecimal mem, AgentProperties p) {
        StringBuilder sb = new StringBuilder();
        if (over(cpu, p.getLoadThrottlePct())) sb.append("CPU ").append(cpu.stripTrailingZeros().toPlainString()).append('%');
        if (over(mem, p.getLoadThrottlePct())) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("메모리 ").append(mem.stripTrailingZeros().toPlainString()).append('%');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static boolean over(BigDecimal v, double pct) {
        return v != null && v.doubleValue() > pct;
    }
}

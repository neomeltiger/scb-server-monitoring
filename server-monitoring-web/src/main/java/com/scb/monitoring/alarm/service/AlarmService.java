package com.scb.monitoring.alarm.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.alarm.domain.Alarm;
import com.scb.monitoring.alarm.dto.AlarmView;
import com.scb.monitoring.alarm.mapper.AlarmMapper;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.mapper.TopologyMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 서버 장애 알람 (tb_alarm).
 * <ul>
 *   <li>SERVER_DOWN (CRITICAL) : 서버 상태가 Down</li>
 *   <li>AGENT_STALE (WARNING)  : 에이전트가 보고하다가 app.agent.stale-after-sec 이상 끊김 (서버가 Down 이면 SERVER_DOWN 만)</li>
 *   <li>조건이 풀리면 자동 해소(CLOSED). 같은 서버·종류의 진행 중 알람은 하나만 유지</li>
 *   <li>점검 : app.alarm.check-interval-ms 주기(기본 60초) + 서버 수정/에이전트 보고 직후</li>
 *   <li>확인/조치 기록(ACK) 은 ADMIN 만 (SecurityConfig : /api/** 변경 요청)</li>
 * </ul>
 * 점검은 한 번에 하나만 실행 (중복 알람 방지) - lock 을 잡은 뒤 트랜잭션 시작/커밋.
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    private final AlarmMapper alarmMapper;
    private final TopologyMapper topologyMapper;
    private final AgentMapper agentMapper;
    private final AgentProperties agentProps;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final int closedKeepHours;
    private final ReentrantLock lock = new ReentrantLock();

    public AlarmService(AlarmMapper alarmMapper, TopologyMapper topologyMapper, AgentMapper agentMapper,
                        AgentProperties agentProps, PlatformTransactionManager txManager,
                        @Value("${app.alarm.closed-keep-hours:24}") int closedKeepHours) {
        this.alarmMapper = alarmMapper;
        this.topologyMapper = topologyMapper;
        this.agentMapper = agentMapper;
        this.agentProps = agentProps;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.closedKeepHours = closedKeepHours;
    }

    // ===================== 조회 / 확인 =====================

    public List<AlarmView> getActive() {
        return readTx.execute(st -> toViews(alarmMapper.selectActiveAlarmList()));
    }

    /** 최근 app.alarm.closed-keep-hours 시간 안에 해소된 알람 (최대 50건) */
    public List<AlarmView> getRecentClosed() {
        Timestamp since = new Timestamp(System.currentTimeMillis() - closedKeepHours * 3600000L);
        return readTx.execute(st -> toViews(alarmMapper.selectClosedAlarmList(since, 50)));
    }

    /** 확인 / 조치 내용 기록 (해소 전까지 여러 번 수정 가능) */
    public AlarmView acknowledge(Long alarmId, String user, String memo) {
        String m = memo == null ? "" : memo.trim();
        if (m.isEmpty()) throw TopologyException.badRequest("조치 내용을 입력하세요.");
        if (m.length() > 500) m = m.substring(0, 500);
        final String actionMemo = m;
        return tx.execute(st -> {
            Alarm a = alarmMapper.selectAlarm(alarmId);
            if (a == null) throw TopologyException.notFound("알람을 찾을 수 없습니다: " + alarmId);
            if (alarmMapper.ackAlarm(alarmId, user, actionMemo) == 0) {
                throw TopologyException.conflict("이미 해소된 알람입니다.");
            }
            log.info("ALARM ACK id={} server={} type={} by {}", alarmId, a.getServerId(), a.getAlarmType(), user);
            return AlarmView.of(alarmMapper.selectAlarm(alarmId));
        });
    }

    // ===================== 점검 =====================

    @Scheduled(initialDelayString = "${app.alarm.initial-delay-ms:10000}",
               fixedDelayString = "${app.alarm.check-interval-ms:60000}")
    public void scheduledCheck() {
        try {
            checkAll();
        } catch (RuntimeException e) {
            log.error("알람 점검 실패", e);
        }
    }

    /** 전체 서버 점검. @return 새로 발생한 알람 수 */
    public int checkAll() {
        lock.lock();
        try {
            Integer opened = tx.execute(st -> {
                Map<Long, Agent> agents = new HashMap<>();
                for (Agent a : agentMapper.selectAgentList()) agents.put(a.getServerId(), a);
                Set<String> active = activeKeys();
                int n = 0;
                for (Server s : topologyMapper.selectServerList()) n += evaluate(s, agents.get(s.getServerId()), active);
                return n;
            });
            return opened == null ? 0 : opened;
        } finally {
            lock.unlock();
        }
    }

    /** 서버 한 대 점검 */
    public void checkServer(Long serverId) {
        lock.lock();
        try {
            tx.execute(st -> {
                Server s = topologyMapper.selectServer(serverId);
                return s == null ? 0 : evaluate(s, agentMapper.selectAgent(serverId), activeKeys());
            });
        } finally {
            lock.unlock();
        }
    }

    /** 현재 트랜잭션(서버 수정, 에이전트 보고)이 커밋된 뒤 점검. 점검 오류는 원래 요청에 영향 없음 */
    public void checkAfterCommit(Long serverId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeCheck(serverId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeCheck(serverId);
            }
        });
    }

    /** 서버 목록 초기화 등 전체가 바뀐 경우 : 커밋 후 전체 점검 */
    public void checkAllAfterCommit() {
        Runnable r = () -> {
            try {
                checkAll();
            } catch (RuntimeException e) {
                log.warn("알람 점검 실패: {}", e.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            r.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                r.run();
            }
        });
    }

    private void safeCheck(Long serverId) {
        try {
            checkServer(serverId);
        } catch (RuntimeException e) {
            log.warn("알람 점검 실패 server={}: {}", serverId, e.getMessage());
        }
    }

    private Set<String> activeKeys() {
        Set<String> keys = new HashSet<>();
        for (Alarm a : alarmMapper.selectActiveAlarmList()) keys.add(key(a.getServerId(), a.getAlarmType()));
        return keys;
    }

    private int evaluate(Server s, Agent agent, Set<String> active) {
        int opened = 0;
        boolean down = s.isDown();
        String name = s.getSystemNm() + " (" + s.getIpAddr() + ")";

        if (sync(s.getServerId(), Alarm.TYPE_SERVER_DOWN, down, active, Alarm.LEVEL_CRITICAL,
                "서버 다운 : " + name + " - 서버/네트워크 상태를 즉시 확인하세요.")) opened++;

        Long agoSec = null;
        if (agent != null && agent.getLastReportDt() != null) {
            agoSec = Math.max(0, (System.currentTimeMillis() - agent.getLastReportDt().getTime()) / 1000);
        }
        // 웹에서 수집 중지 / 데몬 종료한 경우는 알람 아님, 기준은 max(설정값, 수집 주기 x3)
        boolean stale = !down && agoSec != null && !agent.isIntentionallyIdle()
                && agoSec > agent.staleLimitSec(agentProps.getStaleAfterSec());
        if (sync(s.getServerId(), Alarm.TYPE_AGENT_STALE, stale, active, Alarm.LEVEL_WARNING,
                "에이전트 보고 끊김 : " + name + " - 마지막 보고 " + (agoSec == null ? "-" : (agoSec / 60) + "분 전")
                        + ", krcasmrx11_client 실행 상태를 확인하세요.")) opened++;
        return opened;
    }

    /** @return 새로 발생했으면 true */
    private boolean sync(Long serverId, String type, boolean condition, Set<String> active, String level, String message) {
        String k = key(serverId, type);
        if (condition && !active.contains(k)) {
            Alarm a = new Alarm();
            a.setServerId(serverId);
            a.setAlarmType(type);
            a.setAlarmLevel(level);
            a.setMessage(message.length() > 200 ? message.substring(0, 200) : message);
            alarmMapper.insertAlarm(a);
            active.add(k);
            log.warn("ALARM OPEN id={} server={} type={} : {}", a.getAlarmId(), serverId, type, a.getMessage());
            return true;
        }
        if (!condition && active.contains(k)) {
            alarmMapper.closeAlarm(serverId, type);
            active.remove(k);
            log.info("ALARM CLOSE server={} type={}", serverId, type);
        }
        return false;
    }

    private static String key(Long serverId, String type) {
        return serverId + ":" + type;
    }

    private static List<AlarmView> toViews(List<Alarm> list) {
        List<AlarmView> out = new ArrayList<>();
        for (Alarm a : list) out.add(AlarmView.of(a));
        return out;
    }
}

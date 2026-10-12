package com.scb.monitoring.agent.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.agent.domain.Agent;
import com.scb.monitoring.agent.domain.HeartbeatHist;

/** 서버 에이전트 Mapper. SQL: resources/mapper/agent/AgentMapper.xml (ANSI SQL) */
@Mapper
public interface AgentMapper {

    Agent selectAgent(@Param("serverId") Long serverId);

    /** 전체 에이전트 (등록 현황 / 보고 끊김 알람) */
    List<Agent> selectAgentList();

    Long selectServerIdByToken(@Param("tokenHash") String tokenHash);

    int insertAgent(@Param("serverId") Long serverId, @Param("tokenHash") String tokenHash, @Param("issueUser") String issueUser);

    int updateAgentToken(@Param("serverId") Long serverId, @Param("tokenHash") String tokenHash, @Param("issueUser") String issueUser);

    int updateAgentReport(Agent agent);

    int deleteAgent(@Param("serverId") Long serverId);

    // ---- 데몬 (heartbeat / 웹 제어) ----

    /** 데몬 확인 : 상태·PID 기록 (PID 가 바뀌거나 종료 후 다시 오면 시작 시각 갱신) */
    int updateHeartbeat(@Param("serverId") Long serverId, @Param("daemonState") String daemonState,
                        @Param("daemonPid") Integer daemonPid, @Param("agentVer") String agentVer);

    /** 데몬 정보 없이 확인만 (cron 방식 이전 클라이언트) */
    int updatePollOnly(@Param("serverId") Long serverId);

    /** 종료 요청을 데몬에 전달함 */
    int markShutdownSent(@Param("serverId") Long serverId);

    int updateDaemonStopped(@Param("serverId") Long serverId);

    int updateCollect(@Param("serverId") Long serverId, @Param("collectYn") String collectYn,
                      @Param("shutdownReqYn") String shutdownReqYn, @Param("ctlUser") String ctlUser);

    int updateCollectInterval(@Param("serverId") Long serverId, @Param("sec") int sec, @Param("ctlUser") String ctlUser);

    // ---- 하트비트 (v6) ----

    int updateHbBeat(@Param("serverId") Long serverId, @Param("pid") Integer pid, @Param("daemonState") String daemonState);

    int updateHbDaemonState(@Param("serverId") Long serverId, @Param("daemonState") String daemonState);

    int updateRestartReq(@Param("serverId") Long serverId, @Param("yn") String yn, @Param("user") String user);

    int updateHbState(@Param("serverId") Long serverId, @Param("hbState") String hbState);

    int updateHbInterval(@Param("serverId") Long serverId, @Param("sec") int sec, @Param("ctlUser") String ctlUser);

    int insertHbHist(@Param("serverId") Long serverId, @Param("eventCd") String eventCd, @Param("pid") Integer pid,
                     @Param("gapSec") Integer gapSec, @Param("detail") String detail);

    List<HeartbeatHist> selectHbHist(@Param("serverId") Long serverId, @Param("rows") int rows);

    int deleteHbHistBefore(@Param("serverId") Long serverId, @Param("before") java.sql.Timestamp before);

    int deleteHbHistByServer(@Param("serverId") Long serverId);
}

package com.scb.monitoring.srvreport.mapper;

import java.sql.Timestamp;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.srvreport.domain.BasicRow;
import com.scb.monitoring.srvreport.domain.ServerReport;

/** 서버 점검 리포트 Mapper. SQL: resources/mapper/srvreport/ServerReportMapper.xml (ANSI SQL) */
@Mapper
public interface ServerReportMapper {

    int insertReport(ServerReport report);

    // ---- 자동 요청 주기 (관리자 설정)
    com.scb.monitoring.srvreport.domain.ReportPolicy selectPolicy(@Param("serverId") Long serverId);

    List<com.scb.monitoring.srvreport.domain.ReportPolicy> selectDuePolicies(@Param("now") java.sql.Timestamp now);

    int insertPolicy(com.scb.monitoring.srvreport.domain.ReportPolicy policy);

    int updatePolicy(com.scb.monitoring.srvreport.domain.ReportPolicy policy);

    int advancePolicy(@Param("serverId") Long serverId, @Param("prevDt") java.sql.Timestamp prevDt,
                      @Param("nextDt") java.sql.Timestamp nextDt, @Param("lastReqDt") java.sql.Timestamp lastReqDt);

    int deletePolicy(@Param("serverId") Long serverId);

    /** 내용 없이 목록 (최근 순) */
    List<ServerReport> selectReportList(@Param("serverId") Long serverId);

    /** 내용 포함 */
    ServerReport selectReport(@Param("reportId") Long reportId);

    /** 에이전트에 아직 전달하지 않은 요청 (REQUESTED) */
    List<ServerReport> selectRequested(@Param("serverId") Long serverId);

    int markSent(@Param("reportId") Long reportId);

    /** 에이전트 결과 - 관리자 요청이 진행 중(REQUESTED / SENT)일 때만 바뀜 (그 밖은 0) */
    int updateAgentResult(@Param("reportId") Long reportId, @Param("serverId") Long serverId, @Param("statusCd") String statusCd,
                          @Param("warnCnt") int warnCnt, @Param("byteCnt") int byteCnt, @Param("content") String content);

    int updateResult(@Param("reportId") Long reportId, @Param("statusCd") String statusCd, @Param("warnCnt") int warnCnt,
                     @Param("byteCnt") int byteCnt, @Param("content") String content);

    /** 최근 keep 건을 넘는 오래된 리포트 ID */
    List<Long> selectOldReportIds(@Param("serverId") Long serverId, @Param("keep") int keep);

    int deleteReports(@Param("ids") List<Long> ids);

    int deleteReport(@Param("reportId") Long reportId);

    // ---- 서버 삭제 / 초기화 ----
    int deleteReportsByServer(@Param("serverId") Long serverId);

    // ---- 웹 기본 리포트 (클라이언트 1.11 이하 : 웹에 이미 수집된 자료) ----
    /** 최근 자원 (tb_server_metric) */
    BasicRow selectBasicMetric(@Param("serverId") Long serverId);

    /** since 이후 자원 최댓값과 건수 (tb_server_metric_hist) */
    BasicRow selectBasicPeak(@Param("serverId") Long serverId, @Param("since") Timestamp since);

    /** 웹이 관리하는 서비스 (tb_service, 프로세스 관리 화면) */
    List<BasicRow> selectBasicServices(@Param("serverId") Long serverId);

    /** 웹에 등록된 프로세스 (tb_server_process, 구성도 주요 프로세스) */
    List<BasicRow> selectBasicProcesses(@Param("serverId") Long serverId);

    /** 서버 로그 화면에 등록된 로그별 마지막 수집본 (내용 포함) */
    List<BasicRow> selectBasicLogs(@Param("serverId") Long serverId);

    /** since 이후 알람 (최근 순) */
    List<BasicRow> selectBasicAlarms(@Param("serverId") Long serverId, @Param("since") Timestamp since);

    /** since 이후 서비스 제어 명령 (최근 순) */
    List<BasicRow> selectBasicCmds(@Param("serverId") Long serverId, @Param("since") Timestamp since);
}

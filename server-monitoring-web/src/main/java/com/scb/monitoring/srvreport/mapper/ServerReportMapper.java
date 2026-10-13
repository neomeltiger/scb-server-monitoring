package com.scb.monitoring.srvreport.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.srvreport.domain.ServerReport;

/** 서버 점검 리포트 Mapper. SQL: resources/mapper/srvreport/ServerReportMapper.xml (ANSI SQL) */
@Mapper
public interface ServerReportMapper {

    int insertReport(ServerReport report);

    /** 내용 없이 목록 (최근 순) */
    List<ServerReport> selectReportList(@Param("serverId") Long serverId);

    /** 내용 포함 */
    ServerReport selectReport(@Param("reportId") Long reportId);

    /** 에이전트에 아직 전달하지 않은 요청 (REQUESTED) */
    List<ServerReport> selectRequested(@Param("serverId") Long serverId);

    int markSent(@Param("reportId") Long reportId);

    int updateResult(@Param("reportId") Long reportId, @Param("statusCd") String statusCd, @Param("warnCnt") int warnCnt,
                     @Param("byteCnt") int byteCnt, @Param("content") String content);

    /** 최근 keep 건을 넘는 오래된 리포트 ID */
    List<Long> selectOldReportIds(@Param("serverId") Long serverId, @Param("keep") int keep);

    int deleteReports(@Param("ids") List<Long> ids);

    int deleteReport(@Param("reportId") Long reportId);

    // ---- 서버 삭제 / 초기화 ----
    int deleteReportsByServer(@Param("serverId") Long serverId);
}

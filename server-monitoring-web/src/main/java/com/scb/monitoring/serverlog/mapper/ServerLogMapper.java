package com.scb.monitoring.serverlog.mapper;

import java.sql.Timestamp;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.serverlog.domain.LogPolicy;
import com.scb.monitoring.serverlog.domain.LogSnapshot;
import com.scb.monitoring.serverlog.domain.LogSource;

/** 서버 로그 Mapper. SQL: resources/mapper/serverlog/ServerLogMapper.xml (ANSI SQL) */
@Mapper
public interface ServerLogMapper {

    // ---- 요청 주기 (tb_log_policy) ----
    LogPolicy selectPolicy(@Param("serverId") Long serverId);

    int insertPolicy(@Param("serverId") Long serverId, @Param("intervalMin") int intervalMin, @Param("updUser") String updUser);

    int updateInterval(@Param("serverId") Long serverId, @Param("intervalMin") int intervalMin,
                       @Param("nextReqDt") Timestamp nextReqDt, @Param("updUser") String updUser);

    int requestNow(@Param("serverId") Long serverId, @Param("updUser") String updUser);

    /** 에이전트에 요청을 전달함 : 다음 예정 시각 갱신, 즉시 요청 해제 */
    int markRequested(@Param("serverId") Long serverId, @Param("nextReqDt") Timestamp nextReqDt);

    int markReceived(@Param("serverId") Long serverId);

    // ---- 수집 대상 (tb_log_source) ----
    List<LogSource> selectSourceList(@Param("serverId") Long serverId);

    LogSource selectSource(@Param("logId") Long logId);

    int countSourceByPath(@Param("serverId") Long serverId, @Param("logPath") String logPath, @Param("excludeLogId") Long excludeLogId);

    int selectMaxSourceSortOrd(@Param("serverId") Long serverId);

    int insertSource(LogSource source);

    int updateSource(LogSource source);

    int updateSourceResult(@Param("logId") Long logId, @Param("lastStatus") String lastStatus);

    int deleteSource(@Param("logId") Long logId);

    // ---- 수집 결과 (tb_log_snapshot) ----
    int insertSnapshot(LogSnapshot snapshot);

    /** 내용 없이 목록 (최근 순) */
    List<LogSnapshot> selectSnapshotList(@Param("logId") Long logId);

    LogSnapshot selectSnapshot(@Param("snapId") Long snapId);

    /** 최근 keep 건을 넘는 오래된 수집 ID */
    List<Long> selectOldSnapshotIds(@Param("logId") Long logId, @Param("keep") int keep);

    int deleteSnapshots(@Param("ids") List<Long> ids);

    int deleteSnapshotsByLog(@Param("logId") Long logId);

    // ---- 서버 삭제 ----
    int deleteSnapshotsByServer(@Param("serverId") Long serverId);

    int deleteSourcesByServer(@Param("serverId") Long serverId);

    int deletePolicy(@Param("serverId") Long serverId);
}

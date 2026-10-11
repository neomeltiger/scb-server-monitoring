package com.scb.monitoring.svcctl.mapper;

import java.sql.Timestamp;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.svcctl.domain.ServiceCommand;
import com.scb.monitoring.svcctl.domain.ServiceCount;
import com.scb.monitoring.svcctl.domain.ServiceItem;

/** 서비스 목록 / 제어 명령 Mapper. SQL: resources/mapper/svcctl/ServiceCtlMapper.xml (ANSI SQL) */
@Mapper
public interface ServiceCtlMapper {

    // ---- 서비스 목록 (tb_service) ----
    List<ServiceItem> selectServiceList(@Param("serverId") Long serverId);

    /** 전체 서버의 서비스 (서버, service.list 순서) - 메인 홈 */
    List<ServiceItem> selectAllServiceList();

    ServiceItem selectService(@Param("serverId") Long serverId, @Param("svcNm") String svcNm);

    List<ServiceCount> selectServiceCounts();

    int insertService(ServiceItem item);

    int updateServiceDef(ServiceItem item);

    int deleteService(@Param("svcId") Long svcId);

    int updateServiceState(@Param("serverId") Long serverId, @Param("svcNm") String svcNm,
                           @Param("stateCd") String stateCd, @Param("pid") Integer pid);

    int deleteServicesByServer(@Param("serverId") Long serverId);

    ServiceItem selectServiceById(@Param("svcId") Long svcId);

    int selectMaxListNo(@Param("serverId") Long serverId);

    /** 삭제 후 순번(list_no = service.list 줄 순서 = scb_main.sh idx)을 1부터 다시 매김 */
    int updateListNo(@Param("svcId") Long svcId, @Param("listNo") int listNo);

    /** 대상(target)이 targets 중 하나인 최근 명령 (서비스별 제어 이력) */
    List<ServiceCommand> selectCommandsByTargets(@Param("serverId") Long serverId, @Param("targets") List<String> targets,
                                                 @Param("rows") int rows);

    // ---- 서비스 CPU / MEM 이력 (메인 홈 그래프) ----

    /** 이름으로 서비스를 찾아 이력 1건 (등록되지 않은 서비스면 0건) */
    int insertServiceHist(@Param("serverId") Long serverId, @Param("svcNm") String svcNm,
                          @Param("cpuPct") java.math.BigDecimal cpuPct, @Param("memPct") java.math.BigDecimal memPct,
                          @Param("runningYn") String runningYn);

    /** 서비스별 최근 points 건 (시간순) */
    List<com.scb.monitoring.svcctl.domain.ServiceMetricHist> selectServiceHistory(@Param("points") int points);

    int deleteServiceHistBefore(@Param("serverId") Long serverId, @Param("before") Timestamp before);

    int deleteServiceHistByServer(@Param("serverId") Long serverId);

    int deleteServiceHistBySvc(@Param("svcId") Long svcId);

    // ---- 제어 명령 (tb_service_cmd) ----
    int insertCommand(ServiceCommand cmd);

    ServiceCommand selectCommand(@Param("serverId") Long serverId, @Param("cmdId") Long cmdId);

    List<ServiceCommand> selectRecentCommands(@Param("serverId") Long serverId, @Param("rows") int rows);

    List<ServiceCommand> selectPendingCommands(@Param("serverId") Long serverId);

    int countActiveCommands(@Param("serverId") Long serverId);

    /** REQUESTED → SENT (다른 요청이 먼저 가져갔으면 0) */
    int markCommandSent(@Param("cmdId") Long cmdId);

    /** SENT → DONE / FAILED */
    int completeCommand(@Param("serverId") Long serverId, @Param("cmdId") Long cmdId, @Param("stateCd") String stateCd,
                        @Param("exitCd") Integer exitCd, @Param("resultMsg") String resultMsg);

    /** 오래된 REQUESTED / SENT → EXPIRED */
    int expireCommands(@Param("requestedBefore") Timestamp requestedBefore, @Param("sentBefore") Timestamp sentBefore);

    int deleteCommandsByServer(@Param("serverId") Long serverId);
}

package com.scb.monitoring.topology.mapper;

import java.sql.Timestamp;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.topology.domain.MetricHist;
import com.scb.monitoring.topology.domain.NetNode;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.domain.ServerMetric;
import com.scb.monitoring.topology.domain.ServerProcess;

/**
 * 네트워크 구성도 Mapper. SQL 은 resources/mapper/topology/TopologyMapper.xml (ANSI SQL)
 */
@Mapper
public interface TopologyMapper {

    // ---- 네트워크 노드 (tb_net_node) ----
    List<NetNode> selectNodeList();

    NetNode selectNode(@Param("nodeId") Integer nodeId);

    // ---- 서버 (tb_server + tb_net_node + tb_server_metric) ----
    List<Server> selectServerList();

    Server selectServer(@Param("serverId") Long serverId);

    int countServerByIp(@Param("ipAddr") String ipAddr, @Param("excludeServerId") Long excludeServerId);

    int selectMaxServerSortOrd();

    int insertServer(Server server);

    int updateServer(Server server);

    int clearPairReference(@Param("serverId") Long serverId);

    int deleteServer(@Param("serverId") Long serverId);

    // ---- 자원 현황 (tb_server_metric) ----
    int insertMetric(ServerMetric metric);

    int updateMetric(ServerMetric metric);

    int deleteMetric(@Param("serverId") Long serverId);

    // ---- 프로세스 (tb_server_process) ----
    List<ServerProcess> selectProcessList();

    List<ServerProcess> selectProcessListByServer(@Param("serverId") Long serverId);

    ServerProcess selectProcess(@Param("serverId") Long serverId, @Param("procId") Long procId);

    int selectMaxProcessSortOrd(@Param("serverId") Long serverId);

    int insertProcess(ServerProcess process);

    int updateProcess(ServerProcess process);

    int deleteProcess(@Param("serverId") Long serverId, @Param("procId") Long procId);

    int deleteProcessesByServer(@Param("serverId") Long serverId);

    /** 에이전트가 수집한 프로세스 실행 상태 반영 */
    int updateProcessRuntime(ServerProcess process);

    // ---- 수집 이력 (tb_server_metric_hist / tb_process_metric_hist) : 메인 홈 그래프 ----
    int insertMetricHist(MetricHist hist);

    int insertProcessHist(MetricHist hist);

    /** 서버별 최근 points 건 (시간순) */
    List<MetricHist> selectMetricHistory(@Param("points") int points);

    /** 프로세스별 최근 points 건 (시간순) */
    List<MetricHist> selectProcessHistory(@Param("points") int points);

    int deleteMetricHistBefore(@Param("serverId") Long serverId, @Param("before") Timestamp before);

    int deleteProcessHistBefore(@Param("serverId") Long serverId, @Param("before") Timestamp before);

    int deleteMetricHistByServer(@Param("serverId") Long serverId);

    int deleteProcessHistByServer(@Param("serverId") Long serverId);

    int deleteProcessHistByProcess(@Param("procId") Long procId);
}

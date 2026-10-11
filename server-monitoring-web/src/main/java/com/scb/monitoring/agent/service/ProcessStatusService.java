package com.scb.monitoring.agent.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.svcctl.domain.ServiceItem;
import com.scb.monitoring.svcctl.mapper.ServiceCtlMapper;
import com.scb.monitoring.topology.domain.MetricHist;
import com.scb.monitoring.topology.domain.ServerProcess;
import com.scb.monitoring.topology.mapper.TopologyMapper;

/**
 * 서버 정보를 받아오지 못한 프로세스·서비스를 "종료" 상태로 바꾸고 기록합니다.
 * <ul>
 *   <li>서버 단위 : 에이전트 상태가 정상(OK)이 아니면 (미설치·보고 대기·보고 끊김·수집 중지·데몬 종료)
 *       그 서버의 실행 중 프로세스(tb_server_process)·서비스(tb_service) 전부 → 종료
 *       - 알람 점검(AlarmService, 60초 주기)에서 호출</li>
 *   <li>프로세스 단위 : 보고는 들어왔지만 특정 프로세스·서비스 정보가 빠져 있으면 그것만 → 종료
 *       - 에이전트 보고(AgentService.ingestReport)에서 호출</li>
 *   <li>실행 중 → 종료로 바뀔 때만 기록 : 'process-status' 로그(logs/process-status.log) + 프로세스 수집 이력(running_yn = N)</li>
 *   <li>다음 정상 보고가 들어오면 보고된 값으로 다시 바뀜</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ProcessStatusService {

    /** 프로세스 상태 변경 기록 전용 로거 (logback-spring.xml → logs/process-status.log) */
    static final Logger statusLog = LoggerFactory.getLogger("process-status");

    public static final String SVC_STOPPED = "STOPPED";
    public static final String SVC_RUNNING = "RUNNING";

    private final TopologyMapper topologyMapper;
    private final ServiceCtlMapper serviceCtlMapper;

    public ProcessStatusService(TopologyMapper topologyMapper, ServiceCtlMapper serviceCtlMapper) {
        this.topologyMapper = topologyMapper;
        this.serviceCtlMapper = serviceCtlMapper;
    }

    /**
     * 서버 정보 수신 안 됨 : 실행 중인 프로세스·서비스를 모두 종료로.
     * @param reason 화면·로그에 남길 사유 (예: 보고 끊김)
     * @return 종료로 바꾼 건수
     */
    @Transactional
    public int stopAllNoData(Long serverId, String serverName, String reason) {
        int n = 0;
        for (ServerProcess p : topologyMapper.selectProcessListByServer(serverId)) {
            if (p.isRunning()) {
                stopProcess(p, serverName, "서버 정보 수신 안 됨 - " + reason);
                n++;
            }
        }
        for (ServiceItem s : serviceCtlMapper.selectServiceList(serverId)) {
            if (SVC_RUNNING.equals(s.getStateCd())) {
                stopService(s, serverName, "서버 정보 수신 안 됨 - " + reason);
                n++;
            }
        }
        return n;
    }

    /**
     * 보고에 빠진 프로세스·서비스 : 실행 중이던 것만 종료로.
     * @param reportedProcIds 이번 보고에 들어온 프로세스 ID
     * @param reportedSvcNms  이번 보고에 들어온 서비스명 (null 이면 서비스 상태를 보내지 않는 클라이언트 → 서비스는 건드리지 않음)
     */
    @Transactional
    public int stopMissing(Long serverId, String serverName, Set<Long> reportedProcIds, Collection<String> reportedSvcNms) {
        int n = 0;
        for (ServerProcess p : topologyMapper.selectProcessListByServer(serverId)) {
            if (p.isRunning() && !reportedProcIds.contains(p.getProcId())) {
                stopProcess(p, serverName, "보고에 프로세스 정보 없음");
                n++;
            }
        }
        if (reportedSvcNms != null) {
            for (ServiceItem s : serviceCtlMapper.selectServiceList(serverId)) {
                if (SVC_RUNNING.equals(s.getStateCd()) && !reportedSvcNms.contains(s.getSvcNm())) {
                    stopService(s, serverName, "보고에 서비스 정보 없음");
                    n++;
                }
            }
        }
        return n;
    }

    private void stopProcess(ServerProcess p, String serverName, String why) {
        Integer oldPid = p.getPid();
        p.setPid(null);
        p.setCpuPct(BigDecimal.ZERO);
        p.setMemPct(BigDecimal.ZERO);
        p.setRunning(false);
        topologyMapper.updateProcessRuntime(p);

        MetricHist h = new MetricHist();
        h.setServerId(p.getServerId());
        h.setProcId(p.getProcId());
        h.setCpuPct(BigDecimal.ZERO);
        h.setMemPct(BigDecimal.ZERO);
        h.setRunningYn("N");
        h.setSrcCd(MetricHist.SRC_AGENT);
        topologyMapper.insertProcessHist(h);

        statusLog.warn("PROCESS STOPPED server={} [{}] proc={} [{}] pid={} : {}",
                p.getServerId(), serverName, p.getProcId(), p.getProcNm(), oldPid == null ? "-" : oldPid, why);
    }

    private void stopService(ServiceItem s, String serverName, String why) {
        serviceCtlMapper.updateServiceState(s.getServerId(), s.getSvcNm(), SVC_STOPPED, null);
        statusLog.warn("SERVICE STOPPED server={} [{}] svc={} pid={} : {}",
                s.getServerId(), serverName, s.getSvcNm(), s.getPid() == null ? "-" : s.getPid(), why);
    }
}

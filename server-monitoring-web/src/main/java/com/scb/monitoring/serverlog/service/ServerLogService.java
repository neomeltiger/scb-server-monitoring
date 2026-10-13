package com.scb.monitoring.serverlog.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.serverlog.domain.LogPolicy;
import com.scb.monitoring.serverlog.domain.LogSnapshot;
import com.scb.monitoring.serverlog.domain.LogSource;
import com.scb.monitoring.serverlog.dto.LogSourceForm;
import com.scb.monitoring.serverlog.dto.ServerLogView;
import com.scb.monitoring.serverlog.mapper.ServerLogMapper;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.mapper.TopologyMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 서버 로그 주기 요청.
 * <pre>
 *  웹 : 서버별 요청 주기(분) - 화면에서 선택 (0 = 사용 안 함, 1 / 5 / 10 / 30 / 60), [지금 요청]
 *  에이전트가 명령을 가져갈 때(GET /agent-api/v1/commands) 요청 시각이 되었으면
 *     log|로그ID|줄수|경로   를 함께 내려줌 → 에이전트가 마지막 N줄을 POST /agent-api/v1/logs/{로그ID}
 *  수집 결과는 로그별 최근 KEEP_SNAPSHOTS 회 보관
 * </pre>
 * 에이전트(svr_collect_client)는 서버 쪽 허용 목록(SCB_LOG_ALLOW)에 있는 경로만 읽습니다.
 */
@Service
@Transactional(readOnly = true)
public class ServerLogService {

    private static final Logger log = LoggerFactory.getLogger(ServerLogService.class);
    public static final int KEEP_SNAPSHOTS = 20;
    /** 저장할 로그 내용 최대 길이 (문자) */
    static final int MAX_CONTENT_CHARS = 400 * 1024;

    private final ServerLogMapper mapper;
    private final TopologyMapper topologyMapper;
    private final AgentMapper agentMapper;
    private final AgentProperties agentProps;

    public ServerLogService(ServerLogMapper mapper, TopologyMapper topologyMapper, AgentMapper agentMapper, AgentProperties agentProps) {
        this.mapper = mapper;
        this.topologyMapper = topologyMapper;
        this.agentMapper = agentMapper;
        this.agentProps = agentProps;
    }

    // ===================== 화면 =====================

    public ServerLogView getServerLogs(Long serverId) {
        Server s = findServer(serverId);
        return ServerLogView.of(serverId, s.getSystemNm(), policyOf(serverId), mapper.selectSourceList(serverId),
                AgentView.of(agentMapper.selectAgent(serverId), agentProps.getStaleAfterSec()));
    }

    /** 요청 주기 변경 : 바꾼 시점부터 새 주기로 (사용이면 다음 에이전트 확인 때 바로 1회 요청) */
    @Transactional
    public ServerLogView changeInterval(Long serverId, int intervalMin, String user) {
        findServer(serverId);
        if (!LogPolicy.isAllowedInterval(intervalMin)) {
            throw TopologyException.badRequest("요청 주기는 사용 안 함 / 1 / 5 / 10 / 30 / 60분 중 하나입니다.");
        }
        ensurePolicy(serverId, user);
        mapper.updateInterval(serverId, intervalMin, null, user);
        log.info("LOG POLICY server={} interval={}min by {}", serverId, intervalMin, user);
        return getServerLogs(serverId);
    }

    /** [지금 요청] : 에이전트 데몬이 다음에 웹을 확인할 때 (10초) 바로 전달 */
    @Transactional
    public ServerLogView requestNow(Long serverId, String user) {
        findServer(serverId);
        boolean any = false;
        for (LogSource s : mapper.selectSourceList(serverId)) any |= s.isUse();
        if (!any) throw TopologyException.badRequest("수집할 로그가 없습니다. 먼저 로그 파일을 등록하세요.");
        ensurePolicy(serverId, user);
        mapper.requestNow(serverId, user);
        log.info("LOG REQUEST NOW server={} by {}", serverId, user);
        return getServerLogs(serverId);
    }

    @Transactional
    public ServerLogView.Source addSource(Long serverId, LogSourceForm form) {
        findServer(serverId);
        checkPath(form.getPath());
        if (mapper.countSourceByPath(serverId, form.getPath(), null) > 0) {
            throw TopologyException.conflict("이미 등록된 로그 경로입니다: " + form.getPath());
        }
        LogSource s = new LogSource();
        s.setServerId(serverId);
        apply(s, form);
        s.setSortOrd(mapper.selectMaxSourceSortOrd(serverId) + 10);
        mapper.insertSource(s);
        log.info("LOG SOURCE ADD server={} id={} path={}", serverId, s.getLogId(), s.getLogPath());
        return ServerLogView.Source.of(mapper.selectSource(s.getLogId()));
    }

    @Transactional
    public ServerLogView.Source updateSource(Long serverId, Long logId, LogSourceForm form) {
        LogSource s = findSource(serverId, logId);
        checkPath(form.getPath());
        if (mapper.countSourceByPath(serverId, form.getPath(), logId) > 0) {
            throw TopologyException.conflict("이미 등록된 로그 경로입니다: " + form.getPath());
        }
        apply(s, form);
        mapper.updateSource(s);
        return ServerLogView.Source.of(mapper.selectSource(logId));
    }

    @Transactional
    public void deleteSource(Long serverId, Long logId) {
        findSource(serverId, logId);
        mapper.deleteSnapshotsByLog(logId);
        mapper.deleteSource(logId);
        log.info("LOG SOURCE DELETE server={} id={}", serverId, logId);
    }

    public List<ServerLogView.Snapshot> getSnapshots(Long serverId, Long logId) {
        findSource(serverId, logId);
        List<ServerLogView.Snapshot> out = new ArrayList<>();
        for (LogSnapshot n : mapper.selectSnapshotList(logId)) out.add(ServerLogView.Snapshot.of(n));
        return out;
    }

    public ServerLogView.Snapshot getSnapshot(Long serverId, Long snapId) {
        LogSnapshot n = mapper.selectSnapshot(snapId);
        if (n == null || !serverId.equals(n.getServerId())) throw TopologyException.notFound("수집된 로그를 찾을 수 없습니다.");
        return ServerLogView.Snapshot.of(n);
    }

    // ===================== 에이전트 =====================

    /**
     * 에이전트가 명령을 가져갈 때 함께 내려줄 로그 요청 (log|ID|줄수|경로). 요청 시각이 아니면 빈 문자열.
     * 호출 트랜잭션(쓰기) 안에서 다음 예정 시각을 갱신합니다.
     */
    @Transactional
    public String pullLogRequests(Long serverId) {
        List<LogSource> sources = new ArrayList<>();
        for (LogSource s : mapper.selectSourceList(serverId)) if (s.isUse()) sources.add(s);
        if (sources.isEmpty()) return "";

        LogPolicy p = mapper.selectPolicy(serverId);
        long now = System.currentTimeMillis();
        boolean due;
        if (p == null) {
            due = LogPolicy.DEFAULT_INTERVAL_MIN > 0;
            p = LogPolicy.defaults(serverId);
            if (due) mapper.insertPolicy(serverId, p.getIntervalMin(), null);
        } else {
            due = p.isRequestNow()
                    || (p.getIntervalMin() > 0 && (p.getNextReqDt() == null || p.getNextReqDt().getTime() <= now));
        }
        if (!due) return "";

        Timestamp next = p.getIntervalMin() > 0 ? new Timestamp(now + p.getIntervalMin() * 60000L) : null;
        mapper.markRequested(serverId, next);
        StringBuilder sb = new StringBuilder();
        for (LogSource s : sources) {
            sb.append("log|").append(s.getLogId()).append('|').append(s.getTailLines()).append('|').append(s.getLogPath()).append('\n');
        }
        log.info("LOG REQUEST → agent server={} logs={}", serverId, sources.size());
        return sb.toString();
    }

    /** 에이전트가 보낸 로그 (본문 = 마지막 N줄 또는 오류 메시지) */
    @Transactional
    public boolean ingestLog(Long serverId, Long logId, String status, String content) {
        LogSource s = mapper.selectSource(logId);
        if (s == null || !serverId.equals(s.getServerId())) return false;
        String text = content == null ? "" : content;
        if (text.length() > MAX_CONTENT_CHARS) text = text.substring(text.length() - MAX_CONTENT_CHARS);
        LogSnapshot n = new LogSnapshot();
        n.setLogId(logId);
        n.setServerId(serverId);
        n.setStatusCd(LogSnapshot.normalizeStatus(status));
        n.setContent(text);
        n.setLineCnt(countLines(text));
        n.setByteCnt(text.getBytes(StandardCharsets.UTF_8).length);
        mapper.insertSnapshot(n);
        mapper.updateSourceResult(logId, n.getStatusCd());
        mapper.markReceived(serverId);
        List<Long> old = mapper.selectOldSnapshotIds(logId, KEEP_SNAPSHOTS);
        if (!old.isEmpty()) mapper.deleteSnapshots(old);
        return true;
    }

    // ===================== 서버 삭제 =====================

    @Transactional
    public void deleteByServer(Long serverId) {
        mapper.deleteSnapshotsByServer(serverId);
        mapper.deleteSourcesByServer(serverId);
        mapper.deletePolicy(serverId);
    }

    // ===================== 내부 =====================

    private LogPolicy policyOf(Long serverId) {
        LogPolicy p = mapper.selectPolicy(serverId);
        return p != null ? p : LogPolicy.defaults(serverId);
    }

    private void ensurePolicy(Long serverId, String user) {
        if (mapper.selectPolicy(serverId) == null) mapper.insertPolicy(serverId, LogPolicy.DEFAULT_INTERVAL_MIN, user);
    }

    private static void apply(LogSource s, LogSourceForm f) {
        s.setLogNm(f.getName());
        s.setLogPath(f.getPath());
        s.setTailLines(f.getTailLines());
        s.setUseYn(f.isUse() ? "Y" : "N");
    }

    static void checkPath(String path) {
        if (path == null || !path.matches("^/[A-Za-z0-9_./-]+$")) {
            throw TopologyException.badRequest("경로는 / 로 시작하는 절대 경로이며 영문, 숫자, _ . / - 만 쓸 수 있습니다.");
        }
        if (path.contains("..") || path.contains("//") || path.endsWith("/")) {
            throw TopologyException.badRequest("경로에 .. 이나 // 를 쓸 수 없고, 파일 경로여야 합니다.");
        }
    }

    static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return s.charAt(s.length() - 1) == '\n' ? n : n + 1;
    }

    private Server findServer(Long serverId) {
        Server s = topologyMapper.selectServer(serverId);
        if (s == null) throw TopologyException.notFound("서버를 찾을 수 없습니다. (id=" + serverId + ")");
        return s;
    }

    private LogSource findSource(Long serverId, Long logId) {
        LogSource s = mapper.selectSource(logId);
        if (s == null || !serverId.equals(s.getServerId())) throw TopologyException.notFound("로그를 찾을 수 없습니다. (id=" + logId + ")");
        return s;
    }
}

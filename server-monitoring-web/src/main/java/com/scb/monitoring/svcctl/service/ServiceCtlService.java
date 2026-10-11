package com.scb.monitoring.svcctl.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.scb.monitoring.agent.AgentProperties;
import com.scb.monitoring.agent.dto.AgentView;
import com.scb.monitoring.agent.mapper.AgentMapper;
import com.scb.monitoring.svcctl.domain.ServiceCommand;
import com.scb.monitoring.svcctl.domain.ServiceCount;
import com.scb.monitoring.svcctl.domain.ServiceItem;
import com.scb.monitoring.svcctl.dto.CommandForm;
import com.scb.monitoring.svcctl.dto.CommandView;
import com.scb.monitoring.svcctl.dto.ImportForm;
import com.scb.monitoring.svcctl.dto.ImportResult;
import com.scb.monitoring.svcctl.dto.ServerOption;
import com.scb.monitoring.svcctl.dto.ServerServicesView;
import com.scb.monitoring.svcctl.dto.ServiceForm;
import com.scb.monitoring.svcctl.dto.ServiceView;
import com.scb.monitoring.svcctl.mapper.ServiceCtlMapper;
import com.scb.monitoring.topology.domain.Server;
import com.scb.monitoring.topology.mapper.TopologyMapper;
import com.scb.monitoring.topology.service.TopologyException;

/**
 * 프로세스 관리 화면 : service.list 기반 서비스 목록과 제어 명령.
 * 화면에서 요청한 명령은 tb_service_cmd 에 쌓이고, 서버 에이전트(krcasmrx11_client)가
 * 가져가서 scb_main.sh 로 실행한 뒤 결과를 보고합니다. (웹 서버가 직접 서버에 접속하지 않음)
 */
@Service
@Transactional(readOnly = true)
public class ServiceCtlService {

    private static final Logger log = LoggerFactory.getLogger(ServiceCtlService.class);
    private static final int RECENT_COMMANDS = 20;
    private static final int MAX_RESULT = 4000;

    private final ServiceCtlMapper mapper;
    private final TopologyMapper topologyMapper;
    private final AgentMapper agentMapper;
    private final AgentProperties props;

    public ServiceCtlService(ServiceCtlMapper mapper, TopologyMapper topologyMapper, AgentMapper agentMapper, AgentProperties props) {
        this.mapper = mapper;
        this.topologyMapper = topologyMapper;
        this.agentMapper = agentMapper;
        this.props = props;
    }

    // ===================== 화면 조회 =====================

    /** 서버 선택 목록 (서비스 수 포함, 서비스가 있는 서버가 먼저) */
    public List<ServerOption> getServerOptions() {
        Map<Long, Integer> counts = new HashMap<>();
        for (ServiceCount c : mapper.selectServiceCounts()) counts.put(c.getServerId(), c.getSvcCnt());
        List<ServerOption> withSvc = new ArrayList<>();
        List<ServerOption> without = new ArrayList<>();
        for (Server s : topologyMapper.selectServerList()) {
            int n = counts.getOrDefault(s.getServerId(), 0);
            ServerOption o = new ServerOption(s.getServerId(), s.getSystemNm(), s.getIpAddr(), s.getLocationNm(), n);
            (n > 0 ? withSvc : without).add(o);
        }
        withSvc.addAll(without);
        return withSvc;
    }

    /** 서버별 서비스 목록 (프로세스 관리 화면에 등록된 service.list) - 메인 홈 주요 프로세스 미등록 서버에 표시 (읽기 전용) */
    public Map<Long, List<ServiceView>> getServicesByServer() {
        Map<Long, List<ServiceView>> out = new LinkedHashMap<>();
        for (ServiceItem s : mapper.selectAllServiceList()) {
            out.computeIfAbsent(s.getServerId(), k -> new ArrayList<>()).add(ServiceView.of(s));
        }
        return out;
    }

    /** 서비스 목록 + 제어 이력. 오래된 명령을 만료(UPDATE)시키므로 쓰기 트랜잭션 */
    @Transactional
    public ServerServicesView getServerServices(Long serverId) {
        Server server = findServer(serverId);
        expireOldCommands();
        List<ServiceView> services = new ArrayList<>();
        for (ServiceItem s : mapper.selectServiceList(serverId)) services.add(ServiceView.of(s));
        List<CommandView> commands = new ArrayList<>();
        for (ServiceCommand c : mapper.selectRecentCommands(serverId, RECENT_COMMANDS)) commands.add(CommandView.of(c));
        AgentView agent = AgentView.of(agentMapper.selectAgent(serverId), props.getStaleAfterSec());
        return new ServerServicesView(server.getServerId(), server.getSystemNm(), server.getIpAddr(), server.getStatusCd(),
                agent, services, commands, mapper.countActiveCommands(serverId));
    }

    // ===================== 제어 명령 =====================

    @Transactional
    public CommandView requestCommand(Long serverId, CommandForm form, String user) {
        findServer(serverId);
        String target = form.getTarget().trim();
        String cmd = form.getCmd().trim();
        List<ServiceItem> services = mapper.selectServiceList(serverId);
        if (services.isEmpty()) throw TopologyException.badRequest("등록된 서비스가 없습니다. service.list 를 먼저 가져오세요.");

        if (target.startsWith("group:") || target.startsWith("level:")) {
            int n = Integer.parseInt(target.substring(6));
            boolean group = target.startsWith("group:");
            boolean any = services.stream().anyMatch(s -> (group ? s.getGroupNo() : s.getLevelNo()) == n);
            if (!any) throw TopologyException.badRequest((group ? "그룹 " : "Level ") + n + " 에 해당하는 서비스가 없습니다.");
        } else if (!"all".equals(target)) {
            ServiceItem s = services.stream().filter(x -> x.getSvcNm().equals(target)).findFirst()
                    .orElseThrow(() -> TopologyException.badRequest("등록되지 않은 서비스입니다: " + target));
            checkSupported(s, cmd);
        }

        ServiceCommand c = new ServiceCommand();
        c.setServerId(serverId);
        c.setTarget(target);
        c.setCmdCd(cmd);
        c.setReqUser(user);
        mapper.insertCommand(c);
        log.info("SERVICE CMD request id={} server={} {} {} by {}", c.getCmdId(), serverId, cmd, target, user);
        return CommandView.of(mapper.selectCommand(serverId, c.getCmdId()));
    }

    /** 개별 서비스 : 서비스의 CMDS 로 가능한 명령인지 (restart = stop + start, status 는 항상 가능) */
    static void checkSupported(ServiceItem s, String cmd) {
        boolean ok;
        switch (cmd) {
            case "status":  ok = true; break;
            case "restart": ok = s.supports("start") && s.supports("stop"); break;
            default:        ok = s.supports(cmd);
        }
        if (!ok) throw TopologyException.badRequest(s.getSvcNm() + " 은(는) '" + cmd + "' 를 지원하지 않습니다. (CMDS: " + s.getCmds() + ")");
    }

    // ===================== service.list 가져오기 =====================

    @Transactional
    public ImportResult importServiceList(Long serverId, ImportForm form) {
        findServer(serverId);
        ImportResult result = new ImportResult();
        List<ServiceItem> parsed = ServiceListParser.parse(form.getText(), form.getScriptHome(), serverId, result);
        if (parsed.isEmpty()) throw TopologyException.badRequest("가져올 서비스가 없습니다. " + String.join(", ", result.getSkipped()));

        Map<String, ServiceItem> current = new HashMap<>();
        for (ServiceItem s : mapper.selectServiceList(serverId)) current.put(s.getSvcNm(), s);
        for (ServiceItem s : parsed) {
            ServiceItem old = current.remove(s.getSvcNm());
            if (old == null) {
                mapper.insertService(s);
                result.added();
            } else {
                s.setSvcId(old.getSvcId());
                mapper.updateServiceDef(s);
                result.updated();
            }
        }
        for (ServiceItem gone : current.values()) {
            mapper.deleteServiceHistBySvc(gone.getSvcId());
            mapper.deleteService(gone.getSvcId());
            result.removed();
        }
        log.info("SERVICE LIST import server={} added={} updated={} removed={} skipped={}",
                serverId, result.getAdded(), result.getUpdated(), result.getRemoved(), result.getSkipped().size());
        return result;
    }

    // ===================== 서비스 추가 / 수정 / 삭제 (화면) =====================
    // 웹 DB 의 목록만 바뀝니다. 서버의 scb_main.sh 는 서버의 service.list 파일을 쓰므로
    // [service.list 문서 만들기] 로 만든 파일을 서버에 반영해야 제어에 적용됩니다.

    @Transactional
    public ServiceView addService(Long serverId, ServiceForm form) {
        findServer(serverId);
        ServiceItem s = parseOne(serverId, form);
        if (mapper.selectService(serverId, s.getSvcNm()) != null) {
            throw TopologyException.conflict("이미 등록된 서비스명입니다: " + s.getSvcNm());
        }
        s.setListNo(mapper.selectMaxListNo(serverId) + 1);
        mapper.insertService(s);
        log.info("SERVICE ADD server={} {} (no={})", serverId, s.getSvcNm(), s.getListNo());
        return ServiceView.of(mapper.selectServiceById(s.getSvcId()));
    }

    @Transactional
    public ServiceView updateService(Long serverId, Long svcId, ServiceForm form) {
        ServiceItem old = findService(serverId, svcId);
        ServiceItem s = parseOne(serverId, form);
        ServiceItem dup = mapper.selectService(serverId, s.getSvcNm());
        if (dup != null && !dup.getSvcId().equals(svcId)) {
            throw TopologyException.conflict("이미 등록된 서비스명입니다: " + s.getSvcNm());
        }
        s.setSvcId(svcId);
        s.setListNo(old.getListNo());
        mapper.updateServiceDef(s);
        log.info("SERVICE UPDATE server={} {} → {}", serverId, old.getSvcNm(), s.getSvcNm());
        return ServiceView.of(mapper.selectServiceById(svcId));
    }

    @Transactional
    public void deleteService(Long serverId, Long svcId) {
        ServiceItem s = findService(serverId, svcId);
        mapper.deleteServiceHistBySvc(svcId);
        mapper.deleteService(svcId);
        int no = 0;
        for (ServiceItem x : mapper.selectServiceList(serverId)) mapper.updateListNo(x.getSvcId(), ++no);
        log.info("SERVICE DELETE server={} {}", serverId, s.getSvcNm());
    }

    /** 현재 목록을 service.list 형식 문서로 (SCRIPT 는 scriptHome 아래면 상대 경로로) */
    public String exportServiceList(Long serverId, String scriptHome) {
        Server server = findServer(serverId);
        String home = scriptHome == null || scriptHome.trim().isEmpty() ? "/shared/opt/pos" : scriptHome.trim();
        if (home.endsWith("/")) home = home.substring(0, home.length() - 1);
        StringBuilder sb = new StringBuilder();
        sb.append("#=============================================================\n");
        sb.append("# service.list - ").append(server.getSystemNm()).append(" (").append(server.getIpAddr()).append(")\n");
        sb.append("# 생성 : 통합 서버 모니터링 웹 (프로세스 관리) ")
          .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append('\n');
        sb.append("# 위치 : /shared/opt/agent/krcasmrx11_agent/etc/service.list   SCRIPT 기준 : ").append(home).append('\n');
        sb.append("# 줄 순서 = scb_main.sh 번호(idx), 기동은 GROUP 오름차순 / 종료는 역순\n");
        sb.append("#=============================================================\n");
        sb.append("# GROUP|LEVEL|SERVICE_NAME|ACCOUNT|SCRIPT|ARG|CMDS\n");
        for (ServiceItem s : mapper.selectServiceList(serverId)) {
            String script = s.getScriptPath();
            if (script != null && script.startsWith(home + "/")) script = script.substring(home.length() + 1);
            sb.append(s.getGroupNo()).append('|').append(s.getLevelNo()).append('|').append(s.getSvcNm()).append('|')
              .append(s.getAccountNm()).append('|').append(script).append('|')
              .append(s.getScriptArg() == null ? "" : s.getScriptArg()).append('|').append(s.getCmds()).append('\n');
        }
        return sb.toString();
    }

    /** 서비스별 제어 이력 : 서비스명 / 그 서비스의 group:N · level:N / all 을 대상으로 한 명령 */
    public List<CommandView> getServiceCommands(Long serverId, Long svcId, int rows) {
        ServiceItem s = findService(serverId, svcId);
        List<String> targets = java.util.Arrays.asList(s.getSvcNm(), "all", "group:" + s.getGroupNo(), "level:" + s.getLevelNo());
        List<CommandView> out = new ArrayList<>();
        for (ServiceCommand c : mapper.selectCommandsByTargets(serverId, targets, Math.max(1, Math.min(rows, 200)))) {
            out.add(CommandView.of(c));
        }
        return out;
    }

    /** 서버 전체 제어 이력 (팝업) */
    public List<CommandView> getAllCommands(Long serverId, int rows) {
        findServer(serverId);
        List<CommandView> out = new ArrayList<>();
        for (ServiceCommand c : mapper.selectRecentCommands(serverId, Math.max(1, Math.min(rows, 200)))) out.add(CommandView.of(c));
        return out;
    }

    private ServiceItem parseOne(Long serverId, ServiceForm form) {
        ImportResult r = new ImportResult();
        List<ServiceItem> one = ServiceListParser.parse(form.toLine(), form.getScriptHome(), serverId, r);
        if (one.isEmpty()) {
            String why = r.getSkipped().isEmpty() ? "입력값을 확인하세요." : r.getSkipped().get(0).replaceFirst("^\\d+행: ", "");
            throw TopologyException.badRequest(why);
        }
        return one.get(0);
    }

    private ServiceItem findService(Long serverId, Long svcId) {
        ServiceItem s = mapper.selectServiceById(svcId);
        if (s == null || !serverId.equals(s.getServerId())) throw TopologyException.notFound("서비스를 찾을 수 없습니다. (id=" + svcId + ")");
        return s;
    }

    // ===================== 에이전트 연동 =====================

    /** 에이전트가 가져갈 명령 : REQUESTED → SENT */
    @Transactional
    public List<ServiceCommand> pullCommands(Long serverId) {
        expireOldCommands();
        List<ServiceCommand> out = new ArrayList<>();
        for (ServiceCommand c : mapper.selectPendingCommands(serverId)) {
            if (mapper.markCommandSent(c.getCmdId()) == 1) out.add(c);
        }
        return out;
    }

    /** 에이전트 실행 결과 : SENT → DONE(종료코드 0) / FAILED */
    @Transactional
    public boolean completeCommand(Long serverId, Long cmdId, int exitCode, String output) {
        String msg = output == null ? "" : output;
        if (msg.length() > MAX_RESULT) msg = "..." + msg.substring(msg.length() - (MAX_RESULT - 3));
        String state = exitCode == 0 ? ServiceCommand.DONE : ServiceCommand.FAILED;
        boolean ok = mapper.completeCommand(serverId, cmdId, state, exitCode, msg) == 1;
        log.info("SERVICE CMD result id={} server={} exit={} -> {}", cmdId, serverId, exitCode, ok ? state : "IGNORED");
        return ok;
    }

    /** 에이전트가 보고한 서비스 상태 반영 (scb_main.sh status) */
    @Transactional
    public void updateStates(Long serverId, List<String[]> states) {
        for (String[] st : states) {
            mapper.updateServiceState(serverId, st[0], st[1], st[2] == null ? null : Integer.valueOf(st[2]));
            // 클라이언트 1.3~ 는 CPU / MEM 을 함께 보냄 → 메인 홈 서비스 그래프 이력 (이전 클라이언트는 저장 안 함)
            boolean running = "RUNNING".equals(st[1]);
            String cpu = st.length > 3 ? st[3] : null;
            String mem = st.length > 4 ? st[4] : null;
            if (st.length > 5 && "M".equals(st[5]) && !(running && cpu == null)) {
                mapper.insertServiceHist(serverId, st[0],
                        cpu == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(cpu),
                        mem == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(mem), running ? "Y" : "N");
            }
        }
    }

    // ===================== 내부 =====================

    private void expireOldCommands() {
        long now = System.currentTimeMillis();
        mapper.expireCommands(new Timestamp(now - props.getCommandRequestTimeoutMin() * 60000L),
                new Timestamp(now - props.getCommandResultTimeoutMin() * 60000L));
    }

    private Server findServer(Long serverId) {
        Server s = topologyMapper.selectServer(serverId);
        if (s == null) throw TopologyException.notFound("서버를 찾을 수 없습니다. (id=" + serverId + ")");
        return s;
    }
}

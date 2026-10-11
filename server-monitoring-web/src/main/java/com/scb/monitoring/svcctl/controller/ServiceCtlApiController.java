package com.scb.monitoring.svcctl.controller;

import java.util.List;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.scb.monitoring.svcctl.dto.CommandForm;
import com.scb.monitoring.svcctl.dto.CommandView;
import com.scb.monitoring.svcctl.dto.ImportForm;
import com.scb.monitoring.svcctl.dto.ImportResult;
import com.scb.monitoring.svcctl.dto.ServerOption;
import com.scb.monitoring.svcctl.dto.ServerServicesView;
import com.scb.monitoring.svcctl.dto.ServiceForm;
import com.scb.monitoring.svcctl.dto.ServiceView;
import com.scb.monitoring.svcctl.service.ServiceCtlService;

/**
 * 프로세스 관리 화면 API (service.list 기반). 변경/제어(POST)는 ADMIN 만 (SecurityConfig).
 * <pre>
 * GET  /api/services/servers                 서버 선택 목록
 * GET  /api/services/{serverId}              서비스 목록 + 에이전트 상태 + 최근 제어 명령
 * GET  /api/services/by-server              서버별 서비스 목록 (메인 홈)
 * POST   /api/services/{serverId}/items              서비스 추가 (ADMIN)
 * PUT    /api/services/{serverId}/items/{svcId}      서비스 수정 (ADMIN)
 * DELETE /api/services/{serverId}/items/{svcId}      서비스 삭제 (ADMIN)
 * GET    /api/services/{serverId}/service-list?scriptHome=/shared/opt/pos   service.list 문서 (text/plain)
 * GET    /api/services/{serverId}/items/{svcId}/commands?rows=50          서비스별 제어 이력
 * GET    /api/services/{serverId}/commands?rows=100                       전체 제어 이력
 * POST /api/services/{serverId}/commands     제어 요청 {target, cmd}
 * POST /api/services/{serverId}/import       service.list 가져오기 {text, scriptHome}
 * </pre>
 */
@RestController
@RequestMapping("/api/services")
public class ServiceCtlApiController {

    private final ServiceCtlService service;

    public ServiceCtlApiController(ServiceCtlService service) {
        this.service = service;
    }

    @GetMapping("/servers")
    public List<ServerOption> servers() {
        return service.getServerOptions();
    }

    /** 서버별 서비스 목록 {serverId: [...]} - 메인 홈 */
    @GetMapping("/by-server")
    public java.util.Map<Long, List<ServiceView>> byServer() {
        return service.getServicesByServer();
    }

    @GetMapping("/{serverId}")
    public ServerServicesView services(@PathVariable("serverId") Long serverId) {
        return service.getServerServices(serverId);
    }

    @PostMapping("/{serverId}/items")
    public ServiceView addItem(@PathVariable("serverId") Long serverId, @Valid @RequestBody ServiceForm form) {
        return service.addService(serverId, form);
    }

    @PutMapping("/{serverId}/items/{svcId}")
    public ServiceView updateItem(@PathVariable("serverId") Long serverId, @PathVariable("svcId") Long svcId,
                                  @Valid @RequestBody ServiceForm form) {
        return service.updateService(serverId, svcId, form);
    }

    @DeleteMapping("/{serverId}/items/{svcId}")
    public java.util.Map<String, String> deleteItem(@PathVariable("serverId") Long serverId, @PathVariable("svcId") Long svcId) {
        service.deleteService(serverId, svcId);
        return java.util.Collections.singletonMap("message", "삭제되었습니다.");
    }

    @GetMapping(value = "/{serverId}/service-list", produces = "text/plain;charset=UTF-8")
    public String serviceList(@PathVariable("serverId") Long serverId,
                              @RequestParam(value = "scriptHome", required = false) String scriptHome) {
        return service.exportServiceList(serverId, scriptHome);
    }

    @GetMapping("/{serverId}/items/{svcId}/commands")
    public List<CommandView> itemCommands(@PathVariable("serverId") Long serverId, @PathVariable("svcId") Long svcId,
                                          @RequestParam(value = "rows", defaultValue = "50") int rows) {
        return service.getServiceCommands(serverId, svcId, rows);
    }

    @GetMapping("/{serverId}/commands")
    public List<CommandView> allCommands(@PathVariable("serverId") Long serverId,
                                         @RequestParam(value = "rows", defaultValue = "100") int rows) {
        return service.getAllCommands(serverId, rows);
    }

    @PostMapping("/{serverId}/commands")
    public ResponseEntity<CommandView> command(@PathVariable("serverId") Long serverId,
                                               @Valid @RequestBody CommandForm form, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.requestCommand(serverId, form, auth.getName()));
    }

    @PostMapping("/{serverId}/import")
    public ImportResult importList(@PathVariable("serverId") Long serverId, @Valid @RequestBody ImportForm form) {
        return service.importServiceList(serverId, form);
    }
}

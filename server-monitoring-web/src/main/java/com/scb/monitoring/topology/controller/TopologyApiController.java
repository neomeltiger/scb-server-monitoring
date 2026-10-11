package com.scb.monitoring.topology.controller;

import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.scb.monitoring.topology.dto.HistoryView;
import com.scb.monitoring.topology.dto.NodeView;
import com.scb.monitoring.topology.dto.ProcessForm;
import com.scb.monitoring.topology.dto.ProcessView;
import com.scb.monitoring.topology.dto.ServerForm;
import com.scb.monitoring.topology.dto.ServerView;
import com.scb.monitoring.topology.service.TopologyService;

/**
 * 네트워크 구성도 데이터 API (JSON). 화면: /network (templates/network_topology.html)
 *
 * <pre>
 * GET    /api/topology/nodes                                  Hub / Switch 노드
 * GET    /api/topology/servers                                서버 목록 (자원 현황, 프로세스 포함)
 * GET    /api/topology/servers/{id}                           서버 1건
 * POST   /api/topology/servers                                서버 추가
 * PUT    /api/topology/servers/{id}                           서버 수정
 * DELETE /api/topology/servers/{id}                           서버 삭제
 * POST   /api/topology/reset                                  기본 서버 목록으로 초기화
 * POST   /api/topology/servers/{id}/processes                 프로세스 등록
 * PUT    /api/topology/servers/{id}/processes/{procId}        프로세스 수정
 * POST   /api/topology/servers/{id}/processes/{procId}/toggle 프로세스 기동/중지
 * DELETE /api/topology/servers/{id}/processes/{procId}        프로세스 삭제
 * </pre>
 * 변경 요청(POST/PUT/DELETE)은 CSRF 토큰이 필요합니다. (화면의 meta 태그 → script.js apiRequest)
 */
@RestController
@RequestMapping("/api/topology")
public class TopologyApiController {

    private final TopologyService service;

    public TopologyApiController(TopologyService service) {
        this.service = service;
    }

    @GetMapping("/nodes")
    public List<NodeView> nodes() {
        return service.getNodes();
    }

    @GetMapping("/servers")
    public List<ServerView> servers() {
        return service.getServers();
    }

    @GetMapping("/servers/{id}")
    public ServerView server(@PathVariable("id") Long id) {
        return service.getServer(id);
    }

    @PostMapping("/servers")
    public ResponseEntity<ServerView> createServer(@Valid @RequestBody ServerForm form) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createServer(form));
    }

    @PutMapping("/servers/{id}")
    public ServerView updateServer(@PathVariable("id") Long id, @Valid @RequestBody ServerForm form) {
        return service.updateServer(id, form);
    }

    @DeleteMapping("/servers/{id}")
    public ResponseEntity<Void> deleteServer(@PathVariable("id") Long id) {
        service.deleteServer(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset")
    public List<ServerView> reset() {
        return service.resetToDefaults();
    }

    /** 메인 홈 서버별 그래프 : 서버 ID → 최근 points 건 자원/프로세스 이력 */
    @GetMapping("/history")
    public Map<Long, HistoryView> history(@RequestParam(value = "points", defaultValue = "30") int points) {
        return service.getHistory(points);
    }

    @PostMapping("/servers/{id}/processes")
    public ResponseEntity<ProcessView> addProcess(@PathVariable("id") Long id, @Valid @RequestBody ProcessForm form) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addProcess(id, form));
    }

    @PutMapping("/servers/{id}/processes/{procId}")
    public ProcessView updateProcess(@PathVariable("id") Long id, @PathVariable("procId") Long procId,
                                     @Valid @RequestBody ProcessForm form) {
        return service.updateProcess(id, procId, form);
    }

    @PostMapping("/servers/{id}/processes/{procId}/toggle")
    public ProcessView toggleProcess(@PathVariable("id") Long id, @PathVariable("procId") Long procId) {
        return service.toggleProcess(id, procId);
    }

    @DeleteMapping("/servers/{id}/processes/{procId}")
    public ResponseEntity<Void> deleteProcess(@PathVariable("id") Long id, @PathVariable("procId") Long procId) {
        service.deleteProcess(id, procId);
        return ResponseEntity.noContent().build();
    }
}

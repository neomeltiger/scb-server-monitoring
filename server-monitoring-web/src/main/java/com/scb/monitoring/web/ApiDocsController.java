package com.scb.monitoring.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.scb.monitoring.config.ApiSampleCatalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * API 문서 내려받기 : Postman 컬렉션 (Swagger UI 첫 화면 "시작하기" 링크), API 가이드 화면(/admin/api-guide) 데이터·예제 소스.
 * Postman [Import] → 태그별 폴더, API 별 요청·예시 본문, 로그인·CSRF 자동 처리 스크립트, 변수 baseUrl / username / password / agentToken.
 */
@ConditionalOnProperty(prefix = "app.api-docs", name = "enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "7. 기타", description = "웹 콘솔·DB 상태")
@RestController
public class ApiDocsController {

    private final ApiSampleCatalog catalog;

    public ApiDocsController(ApiSampleCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "Postman 컬렉션 내려받기 (v2.1, 전체 API + 로그인·CSRF 스크립트)")
    @GetMapping(value = "/api/docs/postman-collection", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> postmanCollection(@RequestParam(value = "baseUrl", required = false) String baseUrl,
                                                                 HttpServletRequest req) {
        String base = baseUrl != null && baseUrl.matches("^https?://[A-Za-z0-9.:\\-/_\\[\\]]{1,200}$")
                ? baseUrl.replaceAll("/+$", "")
                : ServletUriComponentsBuilder.fromContextPath(req).build().toUriString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"scb-monitoring.postman_collection.json\"")
                .body(catalog.postmanCollection(base));
    }

    @Operation(summary = "API 가이드 화면 데이터 (전체 API + cURL / Java / JavaScript / Python / Postman 예제, ADMIN)")
    @GetMapping(value = "/api/admin/docs/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> catalog(HttpServletRequest req) {
        String base = ServletUriComponentsBuilder.fromContextPath(req).build().toUriString();
        List<Object> list = new ArrayList<>();
        for (ApiSampleCatalog.Entry e : catalog.entries()) list.add(catalog.view(e));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseUrl", base);
        out.put("contextPath", req.getContextPath());
        out.put("guide", catalog.guide(base, req.getContextPath()));
        out.put("entries", list);
        return out;
    }

    @Operation(summary = "전체 API Java 예제 소스 내려받기 (ScbApiExamples.java, ScbApiClient.java 와 함께 사용, ADMIN)")
    @GetMapping(value = "/api/admin/docs/examples-java", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> examplesJava(HttpServletRequest req) {
        String base = ServletUriComponentsBuilder.fromContextPath(req).build().toUriString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ScbApiExamples.java\"")
                .body(catalog.examplesJava(base));
    }
}

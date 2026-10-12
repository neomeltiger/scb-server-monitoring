package com.scb.monitoring.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * API 가이드 (/admin/api-guide, ADMIN) : Postman API 문서처럼 API 목록 + 언어별 예제(cURL / Java / JavaScript / Python / Postman)
 * + 화면에서 바로 호출 + 클라이언트·예제 소스. 데이터는 GET /api/admin/docs/catalog (ApiDocsController, ApiSampleCatalog).
 */
@ConditionalOnProperty(prefix = "app.api-docs", name = "enabled", havingValue = "true", matchIfMissing = true)
@Controller
public class ApiGuidePageController {

    @GetMapping("/admin/api-guide")
    public String apiGuide(Model model) {
        model.addAttribute("menu", "apiguide");
        return "api_guide";
    }
}

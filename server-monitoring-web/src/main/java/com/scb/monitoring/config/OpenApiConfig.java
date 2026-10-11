package com.scb.monitoring.config;

import org.springdoc.core.GroupedOpenApi;
import org.springdoc.core.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Swagger UI / OpenAPI 3 문서 (springdoc-openapi 1.8).
 * <ul>
 *   <li>화면 : /swagger-ui.html (ADMIN 만 - SecurityConfig), 문서 JSON : /v3/api-docs/{그룹}</li>
 *   <li>웹 API(/api/**) : 로그인한 세션 쿠키로 호출, 변경 요청은 CSRF 쿠키(XSRF-TOKEN)를 Swagger UI 가 헤더로 자동 전달</li>
 *   <li>에이전트 API(/agent-api/**) : X-SCB-Agent-Token 헤더 입력 (실제 서버 토큰으로 호출하면 그 서버 상태가 바뀜)</li>
 *   <li>끄기 : 환경변수 SWAGGER_ENABLED=false (application.yml springdoc)</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    static {
        // 컨트롤러의 Authentication 인자는 로그인 세션에서 채워짐 → 문서의 입력 항목에서 제외
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Authentication.class);
    }

    @Bean
    public OpenAPI monitoringOpenApi() {
        return new OpenAPI().info(new Info()
                .title("통합 서버 모니터링 API")
                .version("v5")
                .description("server-monitoring-web REST API. "
                        + "조회(GET)는 로그인 사용자, 변경·삭제·제어(POST/PUT/DELETE)는 ADMIN 만 호출할 수 있습니다. "
                        + "같은 브라우저에서 로그인한 세션으로 호출됩니다."));
    }

    @Bean
    public GroupedOpenApi webApi() {
        return GroupedOpenApi.builder()
                .group("1-web-api")
                .displayName("웹 API (/api)")
                .pathsToMatch("/api/**")
                .build();
    }

    @Bean
    public GroupedOpenApi agentApi() {
        return GroupedOpenApi.builder()
                .group("2-agent-api")
                .displayName("에이전트 API (/agent-api)")
                .pathsToMatch("/agent-api/**")
                .build();
    }
}

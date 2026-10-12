package com.scb.monitoring.config;

import org.springdoc.core.GroupedOpenApi;
import org.springdoc.core.SpringDocUtils;
import org.springdoc.core.customizers.OperationCustomizer;
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
 *   <li>API 마다 Postman API 가이드처럼 호출 예제(cURL / Java / Postman)와 [Try it out] 본문 예시 (ApiSampleCatalog)</li>
 *   <li>내려받기 : Java 클라이언트 /samples/ScbApiClient.java, Postman 컬렉션 GET /api/docs/postman-collection</li>
 *   <li>끄기 : 환경변수 SWAGGER_ENABLED=false (application.yml springdoc)</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    static {
        // 컨트롤러의 Authentication 인자는 로그인 세션에서 채워짐 → 문서의 입력 항목에서 제외
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Authentication.class);
    }

    /** 첫 화면 "시작하기" : 클라이언트·컬렉션 내려받기, cURL / Java 로그인 (각 API 예제의 공통 준비) */
    static final String GUIDE = "server-monitoring-web REST API. "
            + "조회(GET)는 로그인 사용자, 변경·삭제·제어(POST/PUT/DELETE)는 **ADMIN** 만 호출할 수 있습니다. "
            + "이 화면의 [Try it out] 은 지금 로그인한 세션으로 호출됩니다.\n\n"
            + "### 시작하기 (Postman API 가이드처럼 - 각 API 아래에 cURL / Java / Postman 예제)\n"
            + "| 방법 | 준비 |\n|---|---|\n"
            + "| **Postman** | [Postman 컬렉션 내려받기](../api/docs/postman-collection) → Postman [Import] → 컬렉션 Variables 에 "
            + "`baseUrl` / `username` / `password` → [0. 로그인] 폴더 1)·2) 실행 → 원하는 API 실행 (CSRF 헤더는 컬렉션 스크립트가 자동 처리) |\n"
            + "| **Java** | [ScbApiClient.java 내려받기](../samples/ScbApiClient.java) (파일 하나, 외부 라이브러리 없음, JDK 1.8+) → 프로젝트에 복사 → 아래 준비 코드 다음에 각 API 예제 붙여넣기 |\n"
            + "| **cURL** | 아래 로그인 명령 → 각 API 예제 실행 (쿠키 파일 cj.txt 에 세션 유지) |\n\n"
            + "**Java 준비**\n```java\n"
            + "ScbApiClient api = new ScbApiClient(\"http://localhost:8080\");   // Tomcat 배포면 http://서버:8080/server-monitoring-web\n"
            + "api.login(\"admin\", System.getenv(\"SCB_API_PASSWORD\"));          // 비밀번호는 코드에 쓰지 말 것\n"
            + "```\n"
            + "**cURL 준비 (bash)**\n```bash\n"
            + "BASE=http://localhost:8080        # Tomcat 배포면 http://서버:8080/server-monitoring-web\n"
            + "curl -s -c cj.txt -b cj.txt \"$BASE/login\" -o /dev/null\n"
            + "XSRF=$(awk '$6==\"XSRF-TOKEN\"{print $7}' cj.txt)\n"
            + "curl -s -c cj.txt -b cj.txt --data-urlencode username=admin --data-urlencode \"password=$SCB_API_PASSWORD\" "
            + "--data-urlencode \"_csrf=$XSRF\" \"$BASE/login\" -o /dev/null\n"
            + "XSRF=$(awk '$6==\"XSRF-TOKEN\"{print $7}' cj.txt)   # 로그인하면 CSRF 토큰이 바뀜\n"
            + "AGENT_TOKEN=...                   # 에이전트 API 만 (네트워크 구성도에서 발급한 토큰)\n"
            + "```\n";

    @Bean
    public OpenAPI monitoringOpenApi() {
        return new OpenAPI().info(new Info()
                .title("통합 서버 모니터링 API")
                .version("v9")
                .description(GUIDE));
    }

    /** API 마다 호출 예제(cURL / Java / Postman) + [Try it out] 본문 예시 */
    @Bean
    public OperationCustomizer apiSampleCustomizer(ApiSampleCatalog catalog) {
        return (operation, handlerMethod) -> {
            ApiSampleCatalog.Entry e = catalog.find(handlerMethod);
            if (e == null) return operation;
            String desc = operation.getDescription() == null ? "" : operation.getDescription();
            operation.setDescription(desc + catalog.markdown(e));
            if (e.body != null && operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                Object example = e.textBody ? String.valueOf(e.body) : e.body;
                operation.getRequestBody().getContent().forEach((type, media) -> media.setExample(example));
            }
            return operation;
        };
    }

    @Bean
    public GroupedOpenApi webApi(OperationCustomizer apiSampleCustomizer) {
        return GroupedOpenApi.builder()
                .group("1-web-api")
                .displayName("웹 API (/api)")
                .pathsToMatch("/api/**")
                .addOperationCustomizer(apiSampleCustomizer)
                .build();
    }

    @Bean
    public GroupedOpenApi agentApi(OperationCustomizer apiSampleCustomizer) {
        return GroupedOpenApi.builder()
                .group("2-agent-api")
                .displayName("에이전트 API (/agent-api)")
                .pathsToMatch("/agent-api/**")
                .addOperationCustomizer(apiSampleCustomizer)
                .build();
    }
}

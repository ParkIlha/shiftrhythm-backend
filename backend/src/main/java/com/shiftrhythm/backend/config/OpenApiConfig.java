package com.shiftrhythm.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shiftrhythmOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ShiftRhythm API")
                .description("""
                        근무 모드별 수면·식사 루틴 생성 및 AI 연동 API.

                        로그인이 없다. 대신 모든 요청에 X-User-Id 헤더로 사용자를 지목한다:
                        POST /api/onboarding/profile 을 헤더 없이 호출하면 새 사용자가 발급되고(응답 userId),
                        프론트는 그 값을 localStorage에 저장해 이후 모든 요청에 헤더로 붙인다.
                        '새로 시작하기'는 저장값을 지우고 헤더 없이 온보딩을 다시 타는 것이다.
                        헤더를 생략하면 데모 시드 사용자(1)로 동작하므로, 시연에서 미리 채워진
                        리포트·콜렉트북을 보려면 X-User-Id: 1 로 호출하면 된다.
                        """)
                .version("v1"));
    }

    /**
     * X-User-Id는 컨트롤러 인자가 아니라 필터(CurrentUser)에서 읽으므로 springdoc이 자동으로
     * 잡지 못한다. 모든 오퍼레이션에 수동으로 달아 Swagger UI에서 입력·테스트가 되게 한다.
     */
    @Bean
    public OperationCustomizer userIdHeader() {
        return (operation, handlerMethod) -> operation.addParametersItem(new HeaderParameter()
                .name("X-User-Id")
                .required(false)
                .description("현재 사용자 id. 생략하면 데모 시드 사용자(1). 온보딩 프로필 등록만 헤더 없이 호출해 새 사용자를 발급받는다.")
                .schema(new StringSchema().example("1")));
    }
}

package com.shiftrhythm.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shiftrhythmOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ShiftRhythm API")
                .description("근무 모드별 수면·식사 루틴 생성 및 AI 연동 API")
                .version("v1"));
    }
}

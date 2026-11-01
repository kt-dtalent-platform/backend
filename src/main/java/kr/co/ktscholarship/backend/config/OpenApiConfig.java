package kr.co.ktscholarship.backend.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "KT디인재 통합 플랫폼 API",
                version = "v1",
                description = "KT 장학생의 성적증명서 제출 및 검토를 위한 백엔드 API 문서"
        )
)
public class OpenApiConfig {
}

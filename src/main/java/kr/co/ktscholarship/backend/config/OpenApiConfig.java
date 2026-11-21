package kr.co.ktscholarship.backend.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "KT디인재 통합 플랫폼 API",
                version = "v1",
                description = "KT 장학생의 성적증명서 제출 및 검토를 위한 백엔드 API 문서"
        ),
        // 모든 API에 Bearer 토큰 적용(공개 API는 @SecurityRequirements로 제외)
        security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH),
        // Swagger 묶음 순서와 설명. 컨트롤러는 @Tag(name = OpenApiConfig.TAG_...)로 이름만 붙인다
        tags = {
                @Tag(name = OpenApiConfig.TAG_AUTH, description = "로그인·토큰 재발급·로그아웃·비밀번호 변경, 내 정보와 내 계좌"),
                @Tag(name = OpenApiConfig.TAG_DASHBOARD, description = "담당자 첫 화면 요약"),
                @Tag(name = OpenApiConfig.TAG_REQUEST, description = "요청 목록·상세·만들기·고치기·공개, 장학생별 마감 연장"),
                @Tag(name = OpenApiConfig.TAG_REVIEW, description = "요청별 제출 현황·제출 상세, 확인 완료·보완 요청, 엑셀 내보내기"),
                @Tag(name = OpenApiConfig.TAG_SCHOLAR, description = "장학생 목록·상세·등록·수정·상태 변경·내보내기·일괄 등록, 로그인 안내 메일"),
                @Tag(name = OpenApiConfig.TAG_MY_SUBMISSION, description = "내 할 일, 제출 폼, 임시저장, 최종 제출, 재제출, 내 제출 이력"),
                @Tag(name = OpenApiConfig.TAG_FILE, description = "업로드 주소 발급·업로드 완료, 다운로드·원본 보기, 삭제"),
                @Tag(name = OpenApiConfig.TAG_ANALYSIS, description = "성적증명서 OCR 분석 요청과 결과 조회"),
                @Tag(name = OpenApiConfig.TAG_REFERENCE, description = "기수·요청 템플릿·학교")
        }
)
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    // Swagger 묶음 이름. 앞 번호는 화면 흐름 순서
    public static final String TAG_AUTH = "01. 인증·내 정보";
    public static final String TAG_DASHBOARD = "02. 대시보드 (담당자)";
    public static final String TAG_REQUEST = "03. 요청 (담당자)";
    public static final String TAG_REVIEW = "04. 제출 현황·검토 (담당자)";
    public static final String TAG_SCHOLAR = "05. 장학생 (담당자)";
    public static final String TAG_MY_SUBMISSION = "06. 내 제출 (장학생)";
    public static final String TAG_FILE = "07. 파일";
    public static final String TAG_ANALYSIS = "08. 문서 분석";
    public static final String TAG_REFERENCE = "09. 기준 정보";
}

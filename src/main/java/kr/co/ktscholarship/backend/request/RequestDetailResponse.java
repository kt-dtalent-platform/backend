package kr.co.ktscholarship.backend.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 작성 중인 요청 고치기 화면용 모양. 요청 만들기 본문과 같은 필드라 프론트가 받은 값을 고쳐서 그대로 다시 보낼 수 있다.
 * 이름(기수명·템플릿 코드) 대신 id를 주는 이유: 화면이 선택지(기수·템플릿 목록)의 id와 맞춰 체크한다.
 */
public record RequestDetailResponse(
    long id,
    RequestStatus status,
    long programId,
    @Schema(description = "null이면 프로그램 전체 대상(전체 기수)") Long cohortId,
    long templateId,
    String title,
    @Schema(description = "제출 안내") String description,
    int academicYear,
    @Schema(allowableValues = {"1", "2", "SUMMER", "WINTER"}) String semester,
    Instant closesAt,
    @Schema(description = "제출 완료 안내") String completionMessage,
    @Schema(description = "작성 중(DRAFT)이면 null") Instant publishedAt
) {
}

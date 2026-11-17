package kr.co.ktscholarship.backend.request;

/**
 * 요청 만들기·고치기 응답. 프론트는 id로 화면을 이동하고, status로 작성 중임을 확인한다.
 */
public record RequestSaveResponse(long id, RequestStatus status) {
}

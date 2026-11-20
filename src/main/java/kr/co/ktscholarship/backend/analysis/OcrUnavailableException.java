package kr.co.ktscholarship.backend.analysis;

/**
 * OCR을 쓸 수 없음(키 없음, 외부 서비스 오류·시간 초과). 분석 실패로 기록하고 담당자가 원본으로 확인한다.
 * 메시지에 API 키나 문서 내용을 담지 않는다.
 */
public class OcrUnavailableException extends RuntimeException {

    public OcrUnavailableException(String reason) {
        super(reason);
    }

    public OcrUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

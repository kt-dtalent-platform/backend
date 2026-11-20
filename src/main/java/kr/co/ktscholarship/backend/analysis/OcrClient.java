package kr.co.ktscholarship.backend.analysis;

/** 문서 이미지·PDF를 읽어 단어와 위치를 돌려주는 OCR. 서비스를 바꿀 수 있게 인터페이스로 둔다 */
public interface OcrClient {

    /**
     * @throws OcrUnavailableException 설정이 없거나, 외부 서비스가 실패·시간 초과일 때
     */
    OcrPage read(byte[] document, String fileName);
}

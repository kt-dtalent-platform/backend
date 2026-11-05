package kr.co.ktscholarship.backend.submission;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// 내부 조회 모델. 최초 상태/버전/수정 가능 항목 등은 공통 계약 확정 후 API 응답으로 변환
public record SubmissionFormData(
    Request request, List<Field> fields, Submission submission, List<File> files, Revision revision
) {
    public record Request(long id, String title, String description, LocalDateTime requestDueAt, String status) {
    }

    public record Field(
        String fieldKey, String label, String fieldType, boolean required, Map<String, Object> validation
    ) {
    }

    public record Submission(long id, String status, Map<String, Object> answers, int version) {
    }

    public record File(long id, String fieldKey, String originalName, String uploadStatus) {
    }

    public record Revision(long id, String reason, List<String> fieldKeys) {
    }
}

package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// 프론트 RawSubmissionForm과 맞춘 중첩 응답. 최초 진입은 submission=null이며 조회로 초안을 만들지 않는다.
public record SubmissionFormData(
    Request request, List<Field> fields, Submission submission, List<File> files, Revision revision
) {
    public record Request(long id, String title, String description, Instant requestDueAt, String status) {
    }

    public record Field(
        String fieldKey, String label, String fieldType, boolean required,
        List<Option> options, Map<String, Object> validation, String helpText
    ) {
    }

    public record Option(String value, String label) {
    }

    public record Submission(long id, String status, Map<String, Object> answers, int version) {
    }

    public record File(long id, String fieldKey, String originalName, String uploadStatus) {
    }

    public record Revision(long id, String reason, List<String> fieldKeys) {
    }
}

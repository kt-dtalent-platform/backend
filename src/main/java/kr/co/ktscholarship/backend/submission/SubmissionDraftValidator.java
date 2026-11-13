package kr.co.ktscholarship.backend.submission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class SubmissionDraftValidator {
    void validate(Map<String, Object> answers, List<SubmissionDraftRepository.Field> fields) {
        var errors = errors(answers, fields);
        if (!errors.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", errors));
    }

    void validateComplete(Map<String,Object> answers,List<SubmissionDraftRepository.Field> fields,
                          List<SubmissionFinalRepository.FileSnapshot> files) {
        var errors=errors(answers,fields);
        for(var field:fields) {
            if (field.type().equals("FILE")) {
                var matching=files.stream().filter(f->f.fieldKey().equals(field.key())).toList();
                long uploaded=matching.stream().filter(f->f.uploadStatus().equals("UPLOADED")).count();
                if (matching.stream().anyMatch(f->f.uploadStatus().equals("PENDING"))) {
                    errors.add(new FieldError(field.key(),"업로드를 완료하거나 진행 중 파일을 삭제하세요."));
                } else if (field.required() && uploaded==0) {
                    errors.add(new FieldError(field.key(),"필수 파일을 첨부하세요."));
                } else if (field.validation().get("maxFiles") instanceof Number max && uploaded>max.longValue()) {
                    errors.add(new FieldError(field.key(),"파일 개수를 확인하세요."));
                }
            } else {
                Object value=answers.get(field.key());
                boolean missing=value==null || value instanceof String s && s.isBlank() || value instanceof List<?> list && list.isEmpty();
                if (field.required() && missing && errors.stream().noneMatch(e->e.key().equals(field.key()))) {
                    errors.add(new FieldError(field.key(),"필수 항목을 입력하세요."));
                }
            }
        }
        for(var file:files) if(fields.stream().noneMatch(f->f.type().equals("FILE") && f.key().equals(file.fieldKey()))) {
            errors.add(new FieldError(file.fieldKey(),"요청에 없는 첨부파일입니다."));
        }
        if(!errors.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR,Map.of("fieldErrors",errors));
    }

    private ArrayList<FieldError> errors(Map<String,Object> answers,List<SubmissionDraftRepository.Field> fields) {
        var byKey = fields.stream().collect(Collectors.toMap(SubmissionDraftRepository.Field::key, f -> f));
        var errors = new ArrayList<FieldError>();
        answers.forEach((key, value) -> {
            var field = byKey.get(key);
            if (field == null || field.type().equals("FILE")) {
                errors.add(new FieldError(key, "저장할 수 없는 항목입니다."));
            } else if (value != null && !valid(field, value)) {
                errors.add(new FieldError(key, "형식을 확인하세요."));
            }
        });
        return errors;
    }

    private boolean valid(SubmissionDraftRepository.Field field, Object value) {
        return switch (field.type()) {
            case "TEXT", "TEXTAREA" -> value instanceof String s &&
                (!(field.validation().get("maxLength") instanceof Number max) || s.length() <= max.intValue());
            case "NUMBER" -> value instanceof Number n && validNumber(n, field.validation());
            case "CHECKBOX" -> value instanceof Boolean;
            case "DATE" -> value instanceof String s && validDate(s);
            case "SELECT_ONE" -> value instanceof String s && (s.isBlank() || options(field).contains(s));
            case "SELECT_MULTI" -> value instanceof List<?> list && list.stream().allMatch(v -> v instanceof String && options(field).contains(v));
            default -> false;
        };
    }

    private boolean validNumber(Number value, Map<String, Object> rules) {
        try {
            var n = new BigDecimal(value.toString());
            return (!(rules.get("min") instanceof Number min) || n.compareTo(new BigDecimal(min.toString())) >= 0)
                && (!(rules.get("max") instanceof Number max) || n.compareTo(new BigDecimal(max.toString())) <= 0);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean validDate(String value) {
        if (value.isBlank()) return true;
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private Set<?> options(SubmissionDraftRepository.Field field) {
        return field.options().stream().map(o -> o instanceof Map<?, ?> m ? m.get("value") : o).collect(Collectors.toSet());
    }

    record FieldError(String key, String message) {}
}

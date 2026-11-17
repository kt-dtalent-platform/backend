package kr.co.ktscholarship.backend.request;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.request.RequestPublishRepository.RequestField;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 요청 공개. 아래 세 가지를 한 트랜잭션에서 하고, 하나라도 실패하면 모두 되돌린다.
 * 1) 지금 대상 장학생을 스냅샷으로 저장 (공개 뒤 등록된 장학생은 대상이 아님)
 * 2) 템플릿 항목을 이 요청의 항목으로 복사 (이후 템플릿이 바뀌어도 공개된 폼은 그대로)
 * 3) 작성 중 → 진행 중
 */
@Service
public class RequestPublishService {

    private static final TypeReference<List<Map<String, Object>>> FIELDS_TYPE = new TypeReference<>() {};
    private static final Set<String> FIELD_TYPES = Set.of(
        "TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT_ONE", "SELECT_MULTI", "CHECKBOX", "FILE");

    private final RequestPublishRepository repository;
    private final JsonMapper mapper;

    public RequestPublishService(RequestPublishRepository repository, JsonMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public RequestPublishResponse publish(long requestId) {
        var target = repository.lockForPublish(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        if (target.status() != RequestStatus.DRAFT) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_PUBLISHABLE, List.of("status"));
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (!target.closesAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_PUBLISHABLE, List.of("closesAt"));
        }

        List<RequestField> fields = repository.findTemplateFields(target.templateId())
            .map(this::parseFields)
            .orElse(List.of());
        if (fields.isEmpty()) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_PUBLISHABLE, List.of("fields"));
        }

        // 대상이 0명이면 아무것도 넣지 않은 상태에서 멈추도록, 대상 확정을 항목 복사보다 먼저 한다
        int targetCount = repository.insertTargets(requestId, target.programId(), target.cohortId(), now);
        if (targetCount == 0) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_PUBLISHABLE, List.of("targets"));
        }
        repository.insertFields(requestId, fields);

        // 행을 잠근 상태라 0줄일 수 없지만, 잠금이 빠지는 실수에 대비해 한 번 더 확인한다
        if (repository.markPublished(requestId, now) == 0) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_PUBLISHABLE, List.of("status"));
        }
        return new RequestPublishResponse(requestId, RequestStatus.IN_PROGRESS, now, targetCount);
    }

    // 템플릿 JSON을 항목 목록으로. 형식이 틀린 항목이 하나라도 있으면 공개할 수 없는 템플릿으로 본다
    private List<RequestField> parseFields(String json) {
        List<Map<String, Object>> raw;
        try {
            raw = mapper.readValue(json, FIELDS_TYPE);
        } catch (JacksonException exception) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_PUBLISHABLE, List.of("fields"));
        }
        List<RequestField> fields = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            if (!(item.get("fieldKey") instanceof String key) || key.isBlank() || key.length() > 64
                || !(item.get("label") instanceof String label) || label.isBlank() || label.length() > 100
                || !(item.get("fieldType") instanceof String type) || !FIELD_TYPES.contains(type)) {
                throw new BusinessException(ErrorCode.REQUEST_NOT_PUBLISHABLE, List.of("fields"));
            }
            fields.add(new RequestField(
                key, label, type,
                Boolean.TRUE.equals(item.get("required")),
                toJson(item.get("options")),
                toJson(item.get("validation")),
                item.get("helpText") instanceof String help ? help : null));
        }
        return fields;
    }

    private String toJson(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }
}

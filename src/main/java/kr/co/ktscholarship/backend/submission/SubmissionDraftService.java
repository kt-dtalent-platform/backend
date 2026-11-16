package kr.co.ktscholarship.backend.submission;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionDraftService {
    private final SubmissionDraftRepository repository;
    private final SubmissionDraftValidator validator;

    public SubmissionDraftService(SubmissionDraftRepository repository, SubmissionDraftValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional
    public SubmissionDraftResponse save(long scholarId, long requestId, SubmissionDraftRequest body) {
        boolean target = repository.lockTarget(requestId, scholarId);
        var request = repository.findRequest(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        if (!target) throw new BusinessException(ErrorCode.NOT_A_TARGET);
        var existing = repository.findSubmission(requestId, scholarId).orElse(null);
        Instant now = Instant.now();
        if (!request.status().equals("IN_PROGRESS") || !now.isBefore(request.closesAt())
            || existing != null && !existing.status().equals("DRAFT") && !existing.status().equals("REVISION_REQUIRED")) {
            throw new BusinessException(ErrorCode.SUBMISSION_LOCKED);
        }
        int version = existing == null ? 0 : existing.version();
        if (version != body.version() || version == Integer.MAX_VALUE) throw new BusinessException(ErrorCode.VERSION_CONFLICT);

        Map<String, Object> answers = new LinkedHashMap<>(body.answers());
        if (existing != null && existing.status().equals("REVISION_REQUIRED")) {
            var editable = repository.revisionFields(existing.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBMISSION_LOCKED));
            for (var entry : answers.entrySet()) {
                if (!editable.contains(entry.getKey()) && !sameValue(existing.answers().get(entry.getKey()), entry.getValue())) {
                    throw new BusinessException(ErrorCode.SUBMISSION_LOCKED);
                }
            }
            // 프론트는 수정 불가 값도 함께 보낸다. 생략된 기존 값은 삭제하지 않는다.
            var merged = new LinkedHashMap<>(existing.answers());
            merged.putAll(answers);
            answers = merged;
        }
        validator.validate(body.answers(), repository.fields(requestId));
        Instant savedAt = now.truncatedTo(ChronoUnit.SECONDS);
        long id;
        if (existing == null) {
            try {
                id = repository.insert(requestId, scholarId, answers, savedAt);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            }
        } else {
            if (repository.update(existing, answers, savedAt) != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            id = existing.id();
        }
        return new SubmissionDraftResponse(id, existing == null ? "DRAFT" : existing.status(), savedAt, version + 1);
    }

    private boolean sameValue(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return java.util.Objects.equals(left, right);
    }
}

package kr.co.ktscholarship.backend.review;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.review.ReviewDecisionRepository.Target;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담당자 검토 결정: 확인 완료, 보완 요청.
 * 순서: 잠금 → 없음(404) → 상태(422) → version(409) → 본문 검사(400) → 저장.
 * 상태를 version보다 먼저 보는 이유: 이미 확인 완료된 제출이면 화면을 새로 고쳐도 할 수 있는 게 없어서
 * "새로고침 후 다시"(409)보다 "검토할 수 없는 상태"(422)가 정확하다.
 */
@Service
public class ReviewDecisionService {

    private static final Set<String> REVIEWABLE = Set.of("SUBMITTED", "RESUBMITTED");

    private final ReviewDecisionRepository repository;

    public ReviewDecisionService(ReviewDecisionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReviewDecisionResponse confirm(long submissionId, long managerId, ReviewConfirmRequest body) {
        Target target = lockReviewable(submissionId, body.version());
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (repository.confirm(target, managerId, now) == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        repository.history(target, "CONFIRMED", managerId, now);
        return new ReviewDecisionResponse(submissionId, "CONFIRMED", target.version() + 1);
    }

    @Transactional
    public ReviewDecisionResponse requestRevision(long submissionId, long managerId, ReviewRevisionRequest body) {
        Target target = lockReviewable(submissionId, body.version());
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        validate(target, body, now);

        // 같은 항목 키를 순서대로 한 번씩만 (중복은 위 검사에서 이미 거절)
        List<String> fieldKeys = body.fieldKeys().stream().map(String::strip).toList();
        repository.insertRevision(submissionId, fieldKeys, body.reason().strip(), managerId, now);
        if (repository.markRevisionRequired(target, now) == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        repository.history(target, "REVISION_REQUIRED", managerId, now);
        // TODO(#78 머지 후): body.dueAt()이 있으면 이 장학생의 개별 마감(request_targets)으로 저장해 재제출 마감으로 쓴다
        return new ReviewDecisionResponse(submissionId, "REVISION_REQUIRED", target.version() + 1);
    }

    private Target lockReviewable(long submissionId, int version) {
        Target target = repository.lock(submissionId)
            // 작성 중은 장학생이 아직 내지 않은 것이라 담당자에게는 없는 제출이다 (상세 API와 같은 기준)
            .filter(found -> !found.status().equals("DRAFT"))
            .orElseThrow(() -> new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND));
        if (!REVIEWABLE.contains(target.status())) {
            throw new BusinessException(ErrorCode.SUBMISSION_NOT_REVIEWABLE);
        }
        if (target.version() != version) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        return target;
    }

    // 틀린 필드를 모아 한 번에 알려 준다. 항목 키가 틀렸으면 details에 그 키도 함께 담는다
    private void validate(Target target, ReviewRevisionRequest body, Instant now) {
        List<String> invalid = new ArrayList<>();
        List<String> keys = body.fieldKeys().stream().map(String::strip).toList();
        Set<String> known = repository.findFieldKeys(target.requestId());
        List<String> unknown = keys.stream().filter(key -> !known.contains(key)).toList();
        if (!unknown.isEmpty() || !ReviewDecisionRepository.duplicates(keys).isEmpty()) {
            invalid.add("fieldKeys");
        }
        if (body.dueAt() != null && !body.dueAt().isAfter(now)) {
            invalid.add("dueAt");
        }
        if (!invalid.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, invalid);
        }
    }
}

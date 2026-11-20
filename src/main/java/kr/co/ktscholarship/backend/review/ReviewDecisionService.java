package kr.co.ktscholarship.backend.review;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.review.ReviewDecisionRepository.Owner;
import kr.co.ktscholarship.backend.review.ReviewDecisionRepository.Target;
import kr.co.ktscholarship.backend.submission.TargetDeadlineRepository;
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
    private final TargetDeadlineRepository deadlines;

    public ReviewDecisionService(ReviewDecisionRepository repository, TargetDeadlineRepository deadlines) {
        this.repository = repository;
        this.deadlines = deadlines;
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
        // 장학생 재제출과 같은 순서(대상 행 → 제출 행)로 잠가, 서로 상대 잠금을 기다리며 멈추는 일(교착)을 막는다
        Owner owner = repository.findOwner(submissionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND));
        // 대상 행이 없으면(탈퇴 등으로 대상에서 빠진 장학생) 재제출할 수 없는 제출이라 바꾸기 전에 거절한다
        TargetDeadlineRepository.Target deadline = deadlines.find(owner.requestId(), owner.scholarId(), true)
            .orElseThrow(() -> new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND));
        Target target = lockReviewable(submissionId, body.version());
        Instant clock = Instant.now();
        Instant now = clock.truncatedTo(ChronoUnit.SECONDS);
        validate(target, body, clock, dueAt(deadline));

        // 같은 항목 키를 순서대로 한 번씩만 (중복은 위 검사에서 이미 거절)
        List<String> fieldKeys = body.fieldKeys().stream().map(String::strip).toList();
        repository.insertRevision(submissionId, fieldKeys, body.reason().strip(), managerId, now);
        if (repository.markRevisionRequired(target, now) == 0) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        repository.history(target, "REVISION_REQUIRED", managerId, now);
        extendIfLater(deadline, body.dueAt(), managerId, now);
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
    // clock은 자르지 않은 지금 시각. 보완 기한은 저장할 값(초 단위)으로 자른 뒤 비교해, 저장하자마자 지난 기한이 되지 않게 한다
    private void validate(Target target, ReviewRevisionRequest body, Instant clock, Instant currentDueAt) {
        List<String> invalid = new ArrayList<>();
        List<String> keys = body.fieldKeys().stream().map(String::strip).toList();
        Set<String> known = repository.findFieldKeys(target.requestId());
        List<String> unknown = keys.stream().filter(key -> !known.contains(key)).toList();
        if (!unknown.isEmpty() || !ReviewDecisionRepository.duplicates(keys).isEmpty()) {
            invalid.add("fieldKeys");
        }
        if (body.dueAt() != null && !body.dueAt().truncatedTo(ChronoUnit.SECONDS).isAfter(clock)) {
            invalid.add("dueAt");
        }
        // 마감이 지났는데 보완 기한이 없으면 장학생이 고쳐도 다시 낼 수 없다 (재제출은 마감 전까지만)
        if (body.dueAt() == null && !clock.isBefore(currentDueAt)) {
            invalid.add("dueAt");
        }
        if (!invalid.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, invalid);
        }
    }

    // 보완 기한은 그 장학생의 재제출 마감이 된다. 이미 받은 마감을 줄이지 않도록 지금보다 늦을 때만 바꾼다
    private void extendIfLater(TargetDeadlineRepository.Target deadline, Instant dueAt, long managerId, Instant now) {
        if (dueAt == null) {
            return;
        }
        Instant due = dueAt.truncatedTo(ChronoUnit.SECONDS);
        if (due.isAfter(dueAt(deadline))) {
            deadlines.extend(deadline.id(), due, managerId, now);
        }
    }

    // 장학생별 연장 마감이 있으면 그것, 없으면 요청 공통 마감
    private static Instant dueAt(TargetDeadlineRepository.Target deadline) {
        return deadline.extendedDueAt() != null ? deadline.extendedDueAt() : deadline.commonDueAt();
    }
}

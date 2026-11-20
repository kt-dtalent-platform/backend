package kr.co.ktscholarship.backend.review;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import kr.co.ktscholarship.backend.analysis.DocumentAnalysisService;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 담당자의 제출 현황·개별 검토 조회 */
@Service
public class SubmissionReviewService {

    private final SubmissionStatusRepository statusRepository;
    private final SubmissionDetailRepository detailRepository;
    private final DocumentAnalysisService analysisService;

    public SubmissionReviewService(SubmissionStatusRepository statusRepository,
                                   SubmissionDetailRepository detailRepository,
                                   DocumentAnalysisService analysisService) {
        this.analysisService = analysisService;
        this.statusRepository = statusRepository;
        this.detailRepository = detailRepository;
    }

    @Transactional(readOnly = true)
    public SubmissionStatusResponse getStatus(long requestId, String bucket, String keyword, String sort,
                                              int page, int pageSize) {
        var request = statusRepository.findRequest(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (sort != null) {
            // 평점은 답변(JSON) 안에 있어 SQL로 정렬하지 않고, 조건에 맞는 대상자를 모두 읽어 정렬한 뒤 자른다.
            // 한 요청의 대상자는 많아야 수백 명이다
            List<SubmissionStatusResponse.Item> all = statusRepository.findAllItems(requestId, bucket, trimmed);
            List<SubmissionStatusResponse.Item> sorted = all.stream().sorted(comparator(sort)).toList();
            int from = (int) Math.min((long) (page - 1) * pageSize, sorted.size());
            int to = Math.min(from + pageSize, sorted.size());
            return new SubmissionStatusResponse(request, statusRepository.summary(requestId),
                sorted.subList(from, to), page, pageSize, sorted.size());
        }
        return new SubmissionStatusResponse(
            request,
            statusRepository.summary(requestId),
            statusRepository.findItems(requestId, bucket, trimmed, page, pageSize),
            page,
            pageSize,
            statusRepository.count(requestId, bucket, trimmed));
    }

    // 상태 정렬 순서는 화면의 기본 흐름과 같게: 검토 대기 → 보완 필요 → 미제출 → 확인 완료
    private static final List<String> STATUS_ORDER =
        List.of("SUBMITTED", "RESUBMITTED", "REVISION_REQUIRED", "NOT_SUBMITTED", "CONFIRMED");

    /**
     * 열 정렬 "name,asc". 글자는 가나다순, 빈 값은 방향과 상관없이 맨 뒤.
     * 값이 같으면 기본 순서(정렬은 안정적이라 읽어 온 기본 순서가 유지된다)
     */
    static Comparator<SubmissionStatusResponse.Item> comparator(String sort) {
        String[] parts = sort.split(",");
        boolean desc = parts[1].equals("desc");
        Collator korean = Collator.getInstance(Locale.KOREAN);
        return switch (parts[0]) {
            case "name" -> nullsLast(SubmissionStatusResponse.Item::scholarName, korean, desc);
            case "cohort" -> nullsLast(SubmissionStatusResponse.Item::cohortName, korean, desc);
            case "university" -> nullsLast(SubmissionStatusResponse.Item::university, korean, desc);
            case "status" -> nullsLast(item -> STATUS_ORDER.indexOf(item.status()), Comparator.naturalOrder(), desc);
            case "gpa" -> nullsLast(SubmissionStatusResponse.Item::gpa, Comparator.naturalOrder(), desc);
            case "submittedAt" -> nullsLast(SubmissionStatusResponse.Item::submittedAt, Comparator.naturalOrder(), desc);
            default -> throw new IllegalArgumentException("지원하지 않는 정렬 항목");
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> Comparator<SubmissionStatusResponse.Item> nullsLast(
            Function<SubmissionStatusResponse.Item, T> key, Comparator<? super T> order, boolean desc) {
        Comparator<T> direction = desc ? (Comparator<T>) order.reversed() : (Comparator<T>) order;
        return Comparator.comparing(key, Comparator.nullsLast(direction));
    }

    @Transactional(readOnly = true)
    public SubmissionDetailResponse getDetail(long submissionId) {
        var base = detailRepository.findSubmitted(submissionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND));
        return new SubmissionDetailResponse(
            base.id(), base.requestId(), base.requestTitle(), base.scholar(), base.status(), base.submittedAt(),
            base.late(),
            detailRepository.findFields(base.requestId()),
            base.answers(),
            detailRepository.findUploadedFiles(submissionId),
            analysisService.latestForReview(submissionId).orElse(null),
            detailRepository.findHistory(submissionId),
            detailRepository.findRevisions(submissionId),
            base.version());
    }
}

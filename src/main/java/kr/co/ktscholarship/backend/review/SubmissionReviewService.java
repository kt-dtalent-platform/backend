package kr.co.ktscholarship.backend.review;

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
    public SubmissionStatusResponse getStatus(long requestId, String bucket, String keyword, int page, int pageSize) {
        var request = statusRepository.findRequest(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.strip();
        return new SubmissionStatusResponse(
            request,
            statusRepository.summary(requestId),
            statusRepository.findItems(requestId, bucket, trimmed, page, pageSize),
            page,
            pageSize,
            statusRepository.count(requestId, bucket, trimmed));
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

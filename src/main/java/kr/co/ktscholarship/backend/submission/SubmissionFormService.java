package kr.co.ktscholarship.backend.submission;

import java.util.List;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SubmissionFormService {
    private final SubmissionFormRepository repository;

    public SubmissionFormService(SubmissionFormRepository repository) {
        this.repository = repository;
    }

    /** 공개 요청과 본인 대상 여부를 확인한 후 본인 제출·파일·미해결 보완 내용을 조합한다. */
    public SubmissionFormData find(long requestId, long scholarId) {
        var request = repository.findRequest(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        if (!repository.isTarget(requestId, scholarId)) {
            throw new BusinessException(ErrorCode.NOT_A_TARGET);
        }
        var fields = repository.findFields(requestId);
        var submission = repository.findSubmission(requestId, scholarId).orElse(null);
        if (submission == null) {
            return new SubmissionFormData(request, fields, null, List.of(), null);
        }
        SubmissionFormData.Revision revision = null;
        if ("REVISION_REQUIRED".equals(submission.status())) {
            revision = repository.findRevision(submission.id())
                .map(row -> new SubmissionFormData.Revision(row.id(), row.reason(), repository.findRevisionKeys(row.id())))
                .orElse(null);
        }
        return new SubmissionFormData(request, fields, submission, repository.findFiles(submission.id()), revision);
    }
}

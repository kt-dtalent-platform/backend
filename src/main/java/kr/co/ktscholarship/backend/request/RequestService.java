package kr.co.ktscholarship.backend.request;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청의 규칙과 판단을 모으는 곳. Repository가 돌려준 "있다/없다, 몇 줄 바뀌었다"를 보고
 * 어떤 오류로 응답할지(400·404·409)를 여기서 정한다.
 */
@Service
public class RequestService {

    private final RequestDetailRepository detailRepository;
    private final RequestWriteRepository writeRepository;

    public RequestService(RequestDetailRepository detailRepository, RequestWriteRepository writeRepository) {
        this.detailRepository = detailRepository;
        this.writeRepository = writeRepository;
    }

    @Transactional(readOnly = true)
    public RequestDetailResponse getDetail(long requestId) {
        return detailRepository.findById(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
    }

    @Transactional
    public RequestSaveResponse create(long managerId, RequestSaveRequest body) {
        Instant now = Instant.now();
        validate(body, now);
        long id = writeRepository.insertDraft(body, managerId, now);
        return new RequestSaveResponse(id, RequestStatus.DRAFT);
    }

    @Transactional
    public RequestSaveResponse update(long requestId, RequestSaveRequest body) {
        // 없거나 이미 공개된 요청이면 본문을 검사하기 전에 먼저 알려 준다
        RequestStatus status = writeRepository.findStatus(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        if (status != RequestStatus.DRAFT) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_EDITABLE);
        }

        Instant now = Instant.now();
        validate(body, now);

        // 위에서 확인한 뒤 그 사이에 공개됐다면 조건부 UPDATE가 0줄을 고친다
        if (writeRepository.updateDraft(requestId, body, now) == 0) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_EDITABLE);
        }
        return new RequestSaveResponse(requestId, RequestStatus.DRAFT);
    }

    // DB나 현재 시각이 있어야 알 수 있는 검사. 틀린 필드를 모두 모아 한 번에 알려 준다
    private void validate(RequestSaveRequest body, Instant now) {
        List<String> invalidFields = new ArrayList<>();

        boolean programExists = writeRepository.programExists(body.programId());
        if (!programExists) {
            invalidFields.add("programId");
        }
        // 외래 키는 기수가 존재하는지만 본다. 이 프로그램 소속인지는 직접 확인한다
        if (body.cohortId() != null && programExists && !writeRepository.findCohortProgramId(body.cohortId())
            .map(programId -> programId.equals(body.programId()))
            .orElse(false)) {
            invalidFields.add("cohortId");
        }
        if (!writeRepository.activeTemplateExists(body.templateId())) {
            invalidFields.add("templateId");
        }
        if (!body.closesAt().isAfter(now)) {
            invalidFields.add("closesAt");
        }

        if (!invalidFields.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, invalidFields);
        }
    }
}

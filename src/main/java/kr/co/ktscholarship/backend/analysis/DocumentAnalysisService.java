package kr.co.ktscholarship.backend.analysis;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.co.ktscholarship.backend.analysis.DocumentAnalysisRepository.Saved;
import kr.co.ktscholarship.backend.analysis.DocumentAnalysisRepository.Stored;
import kr.co.ktscholarship.backend.analysis.DocumentAnalysisRepository.StoredFile;
import kr.co.ktscholarship.backend.analysis.DocumentAnalysisRepository.Submission;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 문서 분석: 제출 파일 → S3 → OCR → 값 추출 → 입력값 비교 → 저장.
 *
 * <p>OCR 호출(수 초) 동안은 DB 트랜잭션을 열지 않고, 결과를 저장할 때만 짧게 연다.
 * 분석은 검토 보조용이라 제출 상태를 바꾸지 않는다. 최종 판단은 담당자가 한다.
 */
@Service
public class DocumentAnalysisService {

    static final String ANALYZER_VERSION = "upstage-ocr+transcript-rules-2026-11-18";
    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final List<String> COMPARED_KEYS = List.of("gpa", "gpa_scale", "credits");

    private final DocumentAnalysisRepository repository;
    private final AnalysisFileReader files;
    private final OcrClient ocr;
    private final TranscriptExtractor extractor;
    private final AnswerComparator comparator;
    private final TransactionTemplate transaction;

    public DocumentAnalysisService(DocumentAnalysisRepository repository, AnalysisFileReader files, OcrClient ocr,
                                   TranscriptExtractor extractor, AnswerComparator comparator,
                                   TransactionTemplate transaction) {
        this.repository = repository;
        this.files = files;
        this.ocr = ocr;
        this.extractor = extractor;
        this.comparator = comparator;
        this.transaction = transaction;
    }

    public DocumentAnalysisResponse analyze(long userId, boolean manager, long submissionId, long fileId) {
        Submission submission = visibleSubmission(userId, manager, submissionId);
        StoredFile file = repository.findFile(submissionId, fileId)
            .filter(f -> f.uploadStatus().equals("UPLOADED"))
            .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
        if (file.sizeBytes() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        Map<String, String> entered = enteredValues(submission.answers());
        Instant requestedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Saved saved;
        try {
            OcrPage page = ocr.read(files.read(file.s3Key()), file.originalName());
            ExtractedTranscript extracted = extractor.extract(page, submission.target());
            saved = new Saved("COMPLETED", entered, extracted, comparator.compare(new LinkedHashMap<>(entered), extracted),
                null, ANALYZER_VERSION, requestedAt, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        } catch (OcrUnavailableException exception) {
            saved = failed(entered, reasonOf(exception.getMessage()), requestedAt);
        } catch (BusinessException exception) {
            // 저장소에서 파일을 못 꺼낸 경우도 분석 실패로 남겨 담당자가 원본으로 확인하게 한다
            saved = failed(entered, exception.getErrorCode().code(), requestedAt);
        }

        Saved result = saved;
        Long id = transaction.execute(status -> {
            // 제출 행을 잠가 같은 제출의 동시 분석을 한 줄로 세운다(회차가 겹치지 않게).
            // OCR 동안 장학생이 입력값을 바꿨으면 이 결과는 이미 오래된 것이라, 최신 결과를 무효로 만들지 않는다
            boolean current = enteredValues(repository.lockAnswers(submissionId)).equals(entered);
            return repository.save(submissionId, fileId, userId, result, current);
        });
        return toResponse(repository.findById(id).orElseThrow());
    }

    public DocumentAnalysisResponse find(long userId, boolean manager, long analysisId) {
        Stored stored = repository.findById(analysisId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
        if (!manager && stored.scholarId() != userId) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return toResponse(stored);
    }

    /** 담당자 제출 상세에 붙일 최신 결과. 입력값이 바뀌었으면 오래된 결과라 보여 주지 않는다 */
    public Optional<AnswerComparison> latestForReview(long submissionId) {
        return repository.findLatestValid(submissionId)
            .filter(stored -> !outdated(stored))
            .map(stored -> new AnswerComparison(stored.overallResult(), stored.comparisons()));
    }

    private Submission visibleSubmission(long userId, boolean manager, long submissionId) {
        Submission submission = repository.findSubmission(submissionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND));
        if (manager) {
            // 작성 중은 담당자에게 아직 없는 제출이다 (제출 상세와 같은 기준)
            if (submission.status().equals("DRAFT")) {
                throw new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND);
            }
        } else if (submission.scholarId() != userId) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return submission;
    }

    private Saved failed(Map<String, String> entered, String reason, Instant requestedAt) {
        return new Saved("FAILED", entered, null,
            comparator.compare(new LinkedHashMap<>(entered), ExtractedTranscript.unreadable()),
            reason, ANALYZER_VERSION, requestedAt, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private DocumentAnalysisResponse toResponse(Stored stored) {
        return new DocumentAnalysisResponse(stored.id(), stored.submissionId(), stored.fileId(), stored.attemptNumber(),
            stored.status(), stored.overallResult(), stored.comparisons(), stored.failureReason(), outdated(stored),
            stored.requestedAt(), stored.completedAt());
    }

    private boolean outdated(Stored stored) {
        return !enteredValues(stored.currentAnswers()).equals(stored.entered());
    }

    static Map<String, String> enteredValues(Map<String, Object> answers) {
        Map<String, String> entered = new LinkedHashMap<>();
        for (String key : COMPARED_KEYS) {
            Object value = answers.get(key);
            if (value != null && !value.toString().isBlank()) {
                entered.put(key, value.toString().strip());
            }
        }
        return entered;
    }

    // "OCR_REQUEST_FAILED: ResourceAccessException" → "OCR_REQUEST_FAILED"
    private static String reasonOf(String message) {
        if (message == null) {
            return "OCR_FAILED";
        }
        int colon = message.indexOf(':');
        return colon < 0 ? message : message.substring(0, colon);
    }
}

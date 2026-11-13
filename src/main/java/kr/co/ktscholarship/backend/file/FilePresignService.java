package kr.co.ktscholarship.backend.file;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class FilePresignService {
    private final FilePresignRepository repository;
    private final ObjectProvider<S3Presigner> presigner;
    private final S3UploadProperties storage;

    public FilePresignService(FilePresignRepository repository, ObjectProvider<S3Presigner> presigner,
                              S3UploadProperties storage) {
        this.repository = repository;
        this.presigner = presigner;
        this.storage = storage;
    }

    @Transactional
    public FilePresignResponse presign(long userId, long submissionId, FilePresignRequest body) {
        // 제출 행 잠금 아래에서 개수 검사와 생성을 함께 해 동시 발급의 제한 초과를 막는다.
        var submission = repository.lockSubmission(submissionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND));
        if (submission.scholarId() != userId) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        var request = repository.requestState(submission.requestId(), userId);
        Instant now = Instant.now();
        if (!request.target()) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        if (!request.published() || !request.status().equals("IN_PROGRESS") || !now.isBefore(request.closesAt())
            || !List.of("DRAFT", "REVISION_REQUIRED").contains(submission.status())) {
            throw new BusinessException(ErrorCode.SUBMISSION_LOCKED);
        }
        var field = repository.findField(submission.requestId(), body.fieldKey())
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        if (!field.fieldType().equals("FILE")) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (submission.status().equals("REVISION_REQUIRED") && !repository.isRevisionField(submissionId, body.fieldKey())) {
            throw new BusinessException(ErrorCode.SUBMISSION_LOCKED);
        }
        String extension = validate(body, field.validation());
        long maxFiles = positiveInteger(field.validation(), "maxFiles");
        if (repository.countActiveFiles(submissionId, body.fieldKey()) >= maxFiles) {
            throw new BusinessException(ErrorCode.FILE_COUNT_EXCEEDED);
        }
        S3Presigner signer = presigner.getIfAvailable();
        if (signer == null || storage.bucket() == null || storage.bucket().isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
        String key = "submissions/" + submissionId + "/" + UUID.randomUUID() + "." + extension;
        long id = repository.insertPending(submissionId, body, key, now.truncatedTo(ChronoUnit.SECONDS));
        try {
            var signed = signer.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(PutObjectRequest.builder().bucket(storage.bucket()).key(key)
                    .contentType(body.contentType()).build()).build());
            return new FilePresignResponse(id, signed.url().toString(), signed.expiration().truncatedTo(ChronoUnit.SECONDS));
        } catch (SdkClientException exception) {
            // 트랜잭션을 롤백해 발급에 실패한 PENDING 행을 남기지 않는다.
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    private String validate(FilePresignRequest body, Map<String, Object> rules) {
        try {
            MediaType type = MediaType.parseMediaType(body.contentType());
            if (type.isWildcardType() || type.isWildcardSubtype()
                || body.contentType().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (body.fileName().contains("/") || body.fileName().contains("\\")
            || body.fileName().chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (rules == null || !(rules.get("allowedExtensions") instanceof List<?> allowed) || allowed.isEmpty()
            || allowed.stream().anyMatch(e -> !(e instanceof String s) || s.isBlank() || s.startsWith("."))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        int dot = body.fileName().lastIndexOf('.');
        String extension = dot <= 0 ? "" : body.fileName().substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!allowed.contains(extension)) throw new BusinessException(ErrorCode.INVALID_EXTENSION);
        long megabytes = positiveInteger(rules, "maxSizeMb");
        if (BigDecimal.valueOf(body.size()).compareTo(BigDecimal.valueOf(megabytes).multiply(BigDecimal.valueOf(1024L * 1024L))) > 0) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        return extension;
    }

    private long positiveInteger(Map<String, Object> rules, String key) {
        try {
            if (rules == null || !(rules.get(key) instanceof Number number)) throw new ArithmeticException();
            long value = new BigDecimal(number.toString()).longValueExact();
            if (value <= 0) throw new ArithmeticException();
            return value;
        } catch (ArithmeticException | NumberFormatException exception) {
            // 제한값이 없는 항목에 임의 기본 제한이나 무제한 업로드를 적용하지 않는다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}

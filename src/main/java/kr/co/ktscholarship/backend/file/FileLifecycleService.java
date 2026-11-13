package kr.co.ktscholarship.backend.file;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileLifecycleService {
    private final FilePresignRepository submissions;
    private final FileLifecycleRepository files;
    private final FileObjectStore storage;

    public FileLifecycleService(FilePresignRepository submissions, FileLifecycleRepository files, FileObjectStore storage) {
        this.submissions=submissions; this.files=files; this.storage=storage;
    }

    @Transactional(noRollbackFor = FileMismatchException.class)
    public Completed complete(long user, long submissionId, long fileId) {
        var submission = ownedSubmission(user,submissionId);
        var file = files.find(fileId,true).filter(f->f.submissionId()==submissionId && !f.status().equals("DELETED"))
            .orElseThrow(()->new BusinessException(ErrorCode.FILE_NOT_FOUND));
        if (file.status().equals("UPLOADED")) return new Completed(fileId,"UPLOADED",file.size());
        mutable(submission,file.fieldKey());
        var field = submissions.findField(submission.requestId(),file.fieldKey())
            .filter(f->f.fieldType().equals("FILE")).orElseThrow(()->new BusinessException(ErrorCode.INVALID_REQUEST));
        var metadata = storage.head(file.key());
        if (!matches(file,metadata,field.validation())) {
            storage.delete(file.key());
            files.deleted(fileId);
            // 실제 객체를 지운 뒤 DELETED 표시도 유지한다. 다른 실패는 정상 롤백한다.
            throw new FileMismatchException();
        }
        files.uploaded(fileId,metadata.size(),Instant.now().truncatedTo(ChronoUnit.SECONDS));
        return new Completed(fileId,"UPLOADED",metadata.size());
    }

    @Transactional
    public void delete(long user, long fileId) {
        var reference = files.find(fileId,false).orElseThrow(()->new BusinessException(ErrorCode.FILE_NOT_FOUND));
        var submission = ownedSubmission(user,reference.submissionId());
        var file = files.find(fileId,true).orElseThrow(()->new BusinessException(ErrorCode.FILE_NOT_FOUND));
        if (file.status().equals("DELETED")) return;
        mutable(submission,file.fieldKey());
        storage.delete(file.key());
        files.deleted(fileId);
    }

    @Transactional
    public void cleanPending(long fileId) {
        var ref = files.find(fileId,false).orElse(null);
        if (ref==null) return;
        submissions.lockSubmission(ref.submissionId());
        var file=files.find(fileId,true).orElse(null);
        if (file==null || !file.status().equals("PENDING") || !file.createdAt().isBefore(Instant.now().minus(Duration.ofHours(24)))) return;
        storage.delete(file.key());
        files.deleted(fileId);
    }

    private FilePresignRepository.Submission ownedSubmission(long user,long id) {
        var s=submissions.lockSubmission(id).orElseThrow(()->new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND));
        if (s.scholarId()!=user) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        return s;
    }

    private void mutable(FilePresignRepository.Submission s,String field) {
        var request=submissions.requestState(s.requestId(),s.scholarId());
        if (!request.target() || !request.published() || !request.status().equals("IN_PROGRESS")
            || !Instant.now().isBefore(request.closesAt()) || !List.of("DRAFT","REVISION_REQUIRED").contains(s.status())
            || s.status().equals("REVISION_REQUIRED") && !submissions.isRevisionField(s.id(),field)) {
            throw new BusinessException(ErrorCode.FILE_LOCKED);
        }
    }

    private boolean matches(FileLifecycleRepository.FileRow file,FileObjectStore.Metadata actual,Map<String,Object> rules) {
        if (rules==null || !(rules.get("maxSizeMb") instanceof Number max)
            || !(rules.get("allowedExtensions") instanceof List<?> allowed)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        int dot=file.originalName().lastIndexOf('.');
        String extension=dot<=0?"":file.originalName().substring(dot+1).toLowerCase(Locale.ROOT);
        if (actual.size()<=0 || actual.size()!=file.size() || !allowed.contains(extension)
            || BigDecimal.valueOf(actual.size()).compareTo(new BigDecimal(max.toString()).multiply(BigDecimal.valueOf(1048576)))>0) return false;
        try { return MediaType.parseMediaType(file.contentType()).equals(MediaType.parseMediaType(actual.contentType())); }
        catch (IllegalArgumentException e) { return false; }
    }

    public record Completed(long fileId,String uploadStatus,long sizeBytes) {}
    static class FileMismatchException extends BusinessException {
        FileMismatchException() { super(ErrorCode.FILE_MISMATCH); }
    }
}

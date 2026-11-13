package kr.co.ktscholarship.backend.file;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
public class FileDownloadService {
    private final FileLifecycleRepository files;
    private final FileDownloadRepository repository;
    private final ObjectProvider<S3Presigner> presigners;
    private final S3UploadProperties storage;
    public FileDownloadService(FileLifecycleRepository files,FileDownloadRepository repository,
                               ObjectProvider<S3Presigner> presigners,S3UploadProperties storage) {
        this.files=files; this.repository=repository; this.presigners=presigners; this.storage=storage;
    }

    @Transactional
    public Link link(long user,boolean manager,long fileId,Long submissionId,boolean attachment,String ip) {
        var file=files.find(fileId,false).orElseThrow(()->new BusinessException(ErrorCode.FILE_NOT_FOUND));
        if(submissionId!=null && file.submissionId()!=submissionId) throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        if(!manager && repository.owner(file.submissionId())!=user) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        if(!file.status().equals("UPLOADED")) throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        var signer=presigners.getIfAvailable();
        if(signer==null || storage.bucket()==null || storage.bucket().isBlank()) throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        try {
            String filename=file.originalName().replace("\r","").replace("\n","");
            String disposition=(attachment?ContentDisposition.attachment():ContentDisposition.inline())
                .filename(filename,StandardCharsets.UTF_8).build().toString();
            var signed=signer.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(GetObjectRequest.builder().bucket(storage.bucket()).key(file.key())
                    .responseContentDisposition(disposition).build()).build());
            repository.audit(user,fileId,ip,Instant.now().truncatedTo(ChronoUnit.SECONDS));
            return new Link(signed.url().toString(),signed.expiration().truncatedTo(ChronoUnit.SECONDS));
        } catch (SdkClientException e) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE); }
    }
    public record Link(String url,Instant expiresAt) {
        @Override public String toString() { return "Link[url=****, expiresAt="+expiresAt+"]"; }
    }
}

package kr.co.ktscholarship.backend.file;

import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class FileObjectStore {
    private final ObjectProvider<S3Client> clients;
    private final S3UploadProperties settings;

    public FileObjectStore(ObjectProvider<S3Client> clients, S3UploadProperties settings) {
        this.clients = clients;
        this.settings = settings;
    }

    public Metadata head(String key) {
        try {
            var result = client().headObject(b -> b.bucket(settings.bucket()).key(key));
            return new Metadata(result.contentLength(), result.contentType());
        } catch (S3Exception exception) {
            throw new BusinessException(exception.statusCode() == 404 ? ErrorCode.UPLOAD_NOT_FOUND : ErrorCode.STORAGE_UNAVAILABLE);
        } catch (SdkClientException exception) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    public void delete(String key) {
        try {
            client().deleteObject(b -> b.bucket(settings.bucket()).key(key));
        } catch (S3Exception | SdkClientException exception) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    private S3Client client() {
        var client = clients.getIfAvailable();
        if (client == null || settings.bucket() == null || settings.bucket().isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
        return client;
    }

    public record Metadata(long size, String contentType) {}
}

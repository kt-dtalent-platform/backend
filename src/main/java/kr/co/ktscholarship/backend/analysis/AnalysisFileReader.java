package kr.co.ktscholarship.backend.analysis;

import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.file.S3UploadProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 분석할 파일을 S3에서 읽는다. 파일 업로드 코드(file 패키지)는 건드리지 않고 같은 S3 설정을 쓴다.
 */
@Component
public class AnalysisFileReader {

    private final ObjectProvider<S3Client> clients;
    private final S3UploadProperties settings;

    public AnalysisFileReader(ObjectProvider<S3Client> clients, S3UploadProperties settings) {
        this.clients = clients;
        this.settings = settings;
    }

    public byte[] read(String key) {
        S3Client client = clients.getIfAvailable();
        if (client == null || settings.bucket() == null || settings.bucket().isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
        try {
            return client.getObjectAsBytes(b -> b.bucket(settings.bucket()).key(key)).asByteArray();
        } catch (S3Exception exception) {
            throw new BusinessException(exception.statusCode() == 404 ? ErrorCode.UPLOAD_NOT_FOUND : ErrorCode.STORAGE_UNAVAILABLE);
        } catch (SdkClientException exception) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
    }
}

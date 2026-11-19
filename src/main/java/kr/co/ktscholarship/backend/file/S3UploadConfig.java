package kr.co.ktscholarship.backend.file;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableConfigurationProperties(S3UploadProperties.class)
public class S3UploadConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.storage.s3.enabled", havingValue = "true")
    S3Client s3Client(S3UploadProperties properties) {
        return S3Client.builder().region(Region.of(properties.region())).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.storage.s3.enabled", havingValue = "true")
    S3Presigner s3Presigner(S3UploadProperties properties) {
        if (properties.bucket() == null || properties.bucket().isBlank()
            || properties.region() == null || properties.region().isBlank()) {
            throw new IllegalStateException("S3 활성화에는 버킷과 리전 설정이 필요합니다.");
        }
        // SDK 기본 자격 증명 체인 사용. 콘솔 비밀번호·액세스 키를 코드에 저장하지 않는다.
        return S3Presigner.builder().region(Region.of(properties.region())).build();
    }
}

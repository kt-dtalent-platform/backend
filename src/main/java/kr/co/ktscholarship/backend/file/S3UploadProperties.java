package kr.co.ktscholarship.backend.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.storage.s3")
public record S3UploadProperties(boolean enabled, String bucket, String region) {}

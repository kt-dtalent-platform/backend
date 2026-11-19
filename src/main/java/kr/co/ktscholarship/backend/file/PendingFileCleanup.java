package kr.co.ktscholarship.backend.file;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.storage.s3.enabled",havingValue="true")
public class PendingFileCleanup {
    private final FileLifecycleRepository repository;
    private final FileLifecycleService service;
    public PendingFileCleanup(FileLifecycleRepository repository,FileLifecycleService service) { this.repository=repository; this.service=service; }

    @Scheduled(initialDelay=3600000,fixedDelay=3600000)
    public void cleanup() {
        for(long id:repository.stalePending(Instant.now().minus(Duration.ofHours(24)))) {
            try { service.cleanPending(id); }
            catch (RuntimeException e) { LoggerFactory.getLogger(PendingFileCleanup.class).warn("PENDING 파일 정리 실패: fileId={}",id); }
        }
    }
}

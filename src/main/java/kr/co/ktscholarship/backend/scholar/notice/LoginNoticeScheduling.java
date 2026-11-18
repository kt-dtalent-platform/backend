package kr.co.ktscholarship.backend.scholar.notice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="app.login-mail.worker-enabled",havingValue="true",matchIfMissing=true)
class LoginNoticeScheduling {}

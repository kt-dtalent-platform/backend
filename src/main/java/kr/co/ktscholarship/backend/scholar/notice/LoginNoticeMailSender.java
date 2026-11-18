package kr.co.ktscholarship.backend.scholar.notice;

import static kr.co.ktscholarship.backend.scholar.notice.LoginNoticeModels.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
class LoginNoticeMailSender {
    private final ObjectProvider<JavaMailSender> mail;
    private final boolean enabled;
    private final String loginUrl;
    private final String host;
    LoginNoticeMailSender(ObjectProvider<JavaMailSender> mail, @Value("${app.login-mail.enabled:false}") boolean enabled,
                          @Value("${app.login-mail.login-url:}") String loginUrl, @Value("${spring.mail.host:}") String host) {
        this.mail=mail; this.enabled=enabled; this.loginUrl=loginUrl; this.host=host;
    }
    boolean available() {
        return enabled && !host.isBlank() && mail.getIfAvailable()!=null && loginUrl.startsWith("https://");
    }
    void send(Delivery value) throws Exception {
        var sender=mail.getObject();
        var message=sender.createMimeMessage();
        var helper=new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(value.sender().fromAddress(), value.sender().senderName());
        helper.setTo(value.email());
        helper.setSubject("[KT 장학생] 로그인 안내");
        helper.setText("""
            안녕하세요, %s 님.
            로그인 주소: %s
            아이디: %s
            임시 비밀번호: %s

            로그인 후 비밀번호를 변경해 주세요.
            새로 발급된 안내가 있다면 가장 최근 비밀번호를 사용해 주세요.
            """.formatted(value.name(),loginUrl,value.email(),value.temporaryPassword()), false);
        sender.send(message);
    }
}

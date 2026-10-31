package kr.co.ktscholarship.backend.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
// 초기 계정
public class Bootstrap implements ApplicationRunner {
  // DB 접근
  private final Database d;
  // 비밀번호 해시
  private final PasswordEncoder passwords;
  // 초기 담당자 이메일
  private final String email;
  // 초기 비밀번호
  private final String password;

  // 의존성 주입
  public Bootstrap(
      Database d,
      PasswordEncoder passwords,
      @Value("${app.bootstrap.email:}") String email,
      @Value("${app.bootstrap.password:}") String password) {
    this.d = d;
    this.passwords = passwords;
    this.email = email;
    this.password = password;
  }

  @Transactional
  // 초기 담당자 등록
  public void run(ApplicationArguments args) {
    if (email.isBlank() && password.isBlank()) return;
    AuthService.validatePassword(password);
    ApiException.require(
        email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"), 400, "INVALID_EMAIL", "관리자 이메일을 확인해 주세요.");
    if (d.exists("SELECT id FROM users WHERE role='MANAGER'")) return;
    d.update(
        "INSERT INTO users(id,email,password_hash,name,role,must_change_password,temporary_password_expires_at) VALUES(?,?,?,?, 'MANAGER',TRUE,?)",
        d.id(),
        email.toLowerCase(java.util.Locale.ROOT),
        passwords.encode(password),
        "초기 담당자",d.after(86400));
  }
}

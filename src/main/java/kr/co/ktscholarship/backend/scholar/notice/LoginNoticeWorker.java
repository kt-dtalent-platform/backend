package kr.co.ktscholarship.backend.scholar.notice;

import static kr.co.ktscholarship.backend.scholar.notice.LoginNoticeModels.*;
import java.security.SecureRandom;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class LoginNoticeWorker {
    private static final char[] ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private final SecureRandom random=new SecureRandom();
    private final LoginNoticeRepository repository;
    private final LoginNoticeMailSender mail;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;
    LoginNoticeWorker(LoginNoticeRepository repository, LoginNoticeMailSender mail, PasswordEncoder encoder, PlatformTransactionManager manager) {
        this.repository = repository;
        this.mail = mail;
        this.encoder = encoder;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Scheduled(fixedDelayString="${app.login-mail.poll-delay:5000}")
    public void process() {
        if (!mail.available()) {
            return;
        }
        repository.expireInterrupted();
        for (int i = 0; i < 25; i++) {
            if (!processOne()) {
                break;
            }
        }
    }
    boolean processOne() {
        if (!mail.available()) {
            return false;
        }
        Attempt attempt=transaction.execute(status->{
            var id=repository.next().orElse(null);
            if (id == null) {
                return new Attempt(false, null);
            }
            var user=repository.lockAccount(id).orElse(null);
            if (!repository.lockPending(id)) {
                return new Attempt(false, null);
            }
            var sender=repository.sender().orElse(null);
            if(user==null || !user.status().equals("ACTIVE") || (!user.passwordChangeRequired() && !repository.isReissue(id)) || sender==null) {
                repository.status(id, "FAILED", "NOT_ELIGIBLE");
                return new Attempt(true, null);
            }
            char[] chars=new char[12];
            for(int i=0;i<chars.length;i++) chars[i]=ALPHABET[random.nextInt(ALPHABET.length)];
            String password=new String(chars);
            repository.storePassword(id,encoder.encode(password));
            String token=java.util.UUID.randomUUID().toString();
            repository.startDelivery(id,token);
            return new Attempt(true,new Delivery(id,user.name(),user.email(),password,sender,token));
        });
        if (attempt.delivery() == null) {
            return attempt.consumed();
        }
        Delivery delivery=attempt.delivery();
        // 비밀번호 저장 커밋 후에만 발송한다. SMTP 통신 중 DB 잠금은 잡지 않는다.
        String error = null;
        try {
            mail.send(delivery);
        } catch (Exception exception) {
            error = "DELIVERY_FAILED";
        }
        repository.finishDelivery(delivery.scholarId(),delivery.token(),error);
        return true;
    }
    private record Attempt(boolean consumed, Delivery delivery) {}
}

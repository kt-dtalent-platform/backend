package kr.co.ktscholarship.backend.scholar.notice;

import static kr.co.ktscholarship.backend.scholar.notice.LoginNoticeModels.*;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class LoginNoticeRepository {
    private final JdbcClient jdbc;
    LoginNoticeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    Optional<Sender> sender() {
        return jdbc.sql("SELECT from_address AS fromAddress,sender_name AS senderName FROM login_notice_settings WHERE id=1")
            .query(Sender.class).optional();
    }
    void saveSender(Sender value) {
        jdbc.sql("INSERT INTO login_notice_settings(id,from_address,sender_name) VALUES(1,:email,:name) ON DUPLICATE KEY UPDATE from_address=:email,sender_name=:name")
            .param("email", value.fromAddress()).param("name",value.senderName()).update();
    }
    Optional<Account> lockAccount(long id) {
        return jdbc.sql("SELECT id,name,email,password_change_required AS passwordChangeRequired,status FROM users WHERE id=:id AND role='SCHOLAR' AND deleted_at IS NULL FOR UPDATE")
            .param("id",id).query(Account.class).optional();
    }
    Optional<Item> notice(long id) {
        return jdbc.sql("SELECT scholar_id AS scholarId,status,error_code AS errorCode FROM scholar_login_notices WHERE scholar_id=:id")
            .param("id",id).query(Item.class).optional();
    }
    List<Item> notices(List<Long> ids) {
        return jdbc.sql("SELECT scholar_id AS scholarId,status,error_code AS errorCode FROM scholar_login_notices WHERE scholar_id IN (:ids) ORDER BY scholar_id")
            .param("ids",ids).query(Item.class).list();
    }
    void enqueue(long id, long actor, boolean reissue) {
        jdbc.sql("INSERT INTO scholar_login_notices(scholar_id,requested_by,status,reissue) VALUES(:id,:actor,'PENDING',:reissue) ON DUPLICATE KEY UPDATE requested_by=:actor,status='PENDING',reissue=:reissue,error_code=NULL,delivery_token=NULL,updated_at=CURRENT_TIMESTAMP")
            .param("id",id).param("actor",actor).param("reissue",reissue).update();
    }
    Optional<Long> next() {
        return jdbc.sql("SELECT scholar_id FROM scholar_login_notices WHERE status='PENDING' ORDER BY scholar_id LIMIT 1")
            .query(Long.class).optional();
    }
    boolean lockPending(long id) {
        return jdbc.sql("SELECT status FROM scholar_login_notices WHERE scholar_id=:id FOR UPDATE")
            .param("id",id).query(String.class).optional().filter("PENDING"::equals).isPresent();
    }
    boolean isReissue(long id) {
        return jdbc.sql("SELECT reissue FROM scholar_login_notices WHERE scholar_id=:id").param("id",id).query(Boolean.class).single();
    }
    void status(long id, String status, String error) {
        jdbc.sql("UPDATE scholar_login_notices SET status=:status,error_code=:error,updated_at=CURRENT_TIMESTAMP WHERE scholar_id=:id")
            .param("status",status).param("error",error).param("id",id).update();
    }
    void startDelivery(long id, String token) {
        jdbc.sql("UPDATE scholar_login_notices SET status='SENDING',error_code=NULL,delivery_token=:token,updated_at=CURRENT_TIMESTAMP WHERE scholar_id=:id")
            .param("token",token).param("id",id).update();
    }
    void finishDelivery(long id, String token, String error) {
        jdbc.sql("UPDATE scholar_login_notices SET status=:status,error_code=:error,updated_at=CURRENT_TIMESTAMP WHERE scholar_id=:id AND status='SENDING' AND delivery_token=:token")
            .param("status",error==null?"SENT":"FAILED").param("error",error).param("id",id).param("token",token).update();
    }
    /**
     * 임시 비밀번호 저장. 비밀번호를 직접 바꿀 때처럼 이미 나간 로그인도 끊는다:
     * tokens_valid_after 전에 발급된 access token은 요청마다 거절되고(AccountTokenValidator), refresh token은 지운다
     */
    void storePassword(long id, String hash) {
        // DATETIME이 초 단위라 맞춰 저장한다 (비밀번호 변경 AuthService와 같은 기준)
        java.time.Instant now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbc.sql("UPDATE users SET password_hash=:hash,password_change_required=TRUE,failed_login_count=0,locked_until=NULL,tokens_valid_after=:now,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("id",id).param("hash",hash).param("now",kr.co.ktscholarship.backend.common.util.UtcDateTimes.toDb(now)).update();
        jdbc.sql("DELETE FROM refresh_tokens WHERE user_id=:id").param("id",id).update();
        jdbc.sql("INSERT INTO audit_logs(actor_id,action,target_type,target_id,row_count) SELECT requested_by,'ISSUE_LOGIN_NOTICE','SCHOLAR',scholar_id,1 FROM scholar_login_notices WHERE scholar_id=:id")
            .param("id",id).update();
    }
    void expireInterrupted() {
        jdbc.sql("UPDATE scholar_login_notices SET status='FAILED',error_code='DELIVERY_UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE status='SENDING' AND updated_at < :cutoff").param("cutoff", kr.co.ktscholarship.backend.common.util.UtcDateTimes.toDb(java.time.Instant.now().minusSeconds(600))).update();
    }
}

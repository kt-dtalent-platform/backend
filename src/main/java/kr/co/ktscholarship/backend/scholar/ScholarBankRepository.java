package kr.co.ktscholarship.backend.scholar;

import java.time.Instant;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScholarBankRepository {
    private final JdbcClient jdbc;
    public ScholarBankRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Stored> find(long user, boolean lock) {
        return jdbc.sql("""
            SELECT s.bank_name, s.bank_account_number, s.bank_account_holder
            FROM scholar_profiles s JOIN users u ON u.id=s.user_id
            WHERE s.user_id=:id AND u.role='SCHOLAR' AND u.deleted_at IS NULL
            """ + (lock ? " FOR UPDATE" : "")).param("id", user)
            .query((rs, n) -> new Stored(rs.getString("bank_name"), rs.getString("bank_account_number"),
                rs.getString("bank_account_holder"))).optional();
    }

    public void update(long user, ScholarBankAccount account, Instant now) {
        jdbc.sql("""
            UPDATE scholar_profiles SET bank_name=:bank, bank_account_number=:number,
                bank_account_holder=:holder, updated_at=:now WHERE user_id=:id
            """).param("id", user).param("bank", account.bankName()).param("number", account.accountNumber())
            .param("holder", account.accountHolder()).param("now", UtcDateTimes.toDb(now)).update();
        jdbc.sql("""
            INSERT INTO audit_logs(actor_id, action, target_type, target_id, row_count, created_at)
            VALUES(:id,'UPDATE_BANK_ACCOUNT','SCHOLAR',:id,1,:now)
            """).param("id", user).param("now", UtcDateTimes.toDb(now)).update();
    }

    public record Stored(String bank, String number, String holder) {
        @Override public String toString() { return "StoredBankAccount[redacted]"; }
    }
}

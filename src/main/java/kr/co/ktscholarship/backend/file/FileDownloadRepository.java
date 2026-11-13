package kr.co.ktscholarship.backend.file;

import java.time.Instant;
import kr.co.ktscholarship.backend.common.util.UtcDateTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FileDownloadRepository {
    private final JdbcClient jdbc;
    public FileDownloadRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    long owner(long submission) {
        return jdbc.sql("SELECT scholar_id FROM submissions WHERE id=:id").param("id",submission).query(Long.class).single();
    }
    void audit(long user,long file,String ip,Instant now) {
        jdbc.sql("""
            INSERT INTO audit_logs(actor_id,action,target_type,target_id,row_count,ip_address,created_at)
            VALUES(:user,'DOWNLOAD_FILE','FILE',:file,1,:ip,:now)
            """).param("user",user).param("file",file).param("ip",ip,java.sql.Types.VARCHAR)
            .param("now",UtcDateTimes.toDb(now)).update();
    }
}

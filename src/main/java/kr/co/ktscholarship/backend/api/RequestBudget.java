package kr.co.ktscholarship.backend.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
// 공유 요청 제한
public class RequestBudget {
  // DB 접근
  private final Database d;
  // 제한 적용
  private final boolean enabled;

  // 제한 설정
  public RequestBudget(Database d,@Value("${app.rate-limit.enabled:true}")boolean enabled){this.d=d;this.enabled=enabled;}

  // 독립 제한 기록
  @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
  public boolean allow(String scope,String identity,int maximum){
    if(!enabled)return true;
    String key=AuthService.hash(scope+":"+identity);long minute=System.currentTimeMillis()/60000;
    d.update("INSERT INTO request_budgets(key_hash,window_start,hit_count) VALUES(?,?,1) ON DUPLICATE KEY UPDATE hit_count=CASE WHEN window_start<VALUES(window_start) THEN 1 ELSE hit_count+1 END,window_start=CASE WHEN window_start<VALUES(window_start) THEN VALUES(window_start) ELSE window_start END",key,minute);
    return Database.number(d.one("SELECT hit_count FROM request_budgets WHERE key_hash=?",key),"hit_count")<=maximum;
  }
}

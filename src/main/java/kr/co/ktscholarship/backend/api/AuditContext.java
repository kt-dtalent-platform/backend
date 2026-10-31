package kr.co.ktscholarship.backend.api;

// 요청 추적 정보
public final class AuditContext {
  // 요청 문맥
  public record Value(String requestId,String address){}
  // 스레드별 정보
  private static final ThreadLocal<Value> CURRENT=new ThreadLocal<>();
  // 직접 생성 제한
  private AuditContext(){}
  // 현재 요청
  public static Value current(){return CURRENT.get();}
  // 요청 등록
  static void set(Value value){CURRENT.set(value);}
  // 요청 정리
  static void clear(){CURRENT.remove();}
}

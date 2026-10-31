package kr.co.ktscholarship.backend.api;

import static kr.co.ktscholarship.backend.api.ApiException.require;
import static kr.co.ktscholarship.backend.api.Database.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
// 세션 및 다중 인증
public class AuthService {
  // 인증 사용자
  public record Actor(long id,String role,boolean mustChangePassword,long sessionId){
    // 내부 검증 사용자
    public Actor(long id,String role){this(id,role,false,0);}
    // 담당자 권한
    public boolean manager(){return "MANAGER".equals(role);}
  }
  // DB 접근
  private final Database d;
  // 비밀번호 검증
  private final PasswordEncoder passwords;
  // 공유 제한
  private final RequestBudget budget;
  // 비밀 암호화
  private final SecretBox box;
  // 담당 범위
  private final WorkflowService workflow;
  // 관리자 다중 인증
  private final boolean adminMfa;
  // 최대 세션 수명
  private final int sessionSeconds;
  // 유휴 세션 수명
  private final int idleSeconds;
  // 보안 난수
  private final SecureRandom random=new SecureRandom();
  // 계정 존재 은닉
  private final String dummy;

  // 인증 설정
  public AuthService(Database d,PasswordEncoder passwords,RequestBudget budget,SecretBox box,WorkflowService workflow,
      @Value("${app.auth.admin-mfa-required:false}")boolean adminMfa,@Value("${app.auth.session-seconds:28800}")int sessionSeconds,@Value("${app.auth.idle-seconds:1800}")int idleSeconds){
    if(sessionSeconds<900||sessionSeconds>604800||idleSeconds<900||idleSeconds>sessionSeconds)throw new IllegalArgumentException("INVALID_SESSION_POLICY");
    this.d=d;this.passwords=passwords;this.budget=budget;this.box=box;this.workflow=workflow;this.adminMfa=adminMfa;this.sessionSeconds=sessionSeconds;this.idleSeconds=idleSeconds;dummy=passwords.encode(UUID.randomUUID().toString());
  }

  // 토큰 해시
  public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  // 보안 토큰
  private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
  // 상태 플래그
  private static boolean flag(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()!=0);}

  @Transactional(noRollbackFor=ApiException.class,timeout=15)
  // 로그인 및 초기 변경 제한
  public Object login(String email,String password){
    String normalized=email.toLowerCase(Locale.ROOT);
    if(AuditContext.current()!=null)require(budget.allow("account-login",normalized,20),429,"RATE_LIMITED","인증 요청이 많습니다.");
    require(password.getBytes(StandardCharsets.UTF_8).length<=72,401,"INVALID_CREDENTIALS","이메일 또는 비밀번호를 확인해 주세요.");
    var rows=d.rows("SELECT * FROM users WHERE email=? AND deleted_at IS NULL FOR UPDATE",normalized);
    if(rows.isEmpty()){passwords.matches(password,dummy);throw new ApiException(401,"INVALID_CREDENTIALS","이메일 또는 비밀번호를 확인해 주세요.");}
    var user=rows.getFirst();long id=number(user,"id");
    boolean blocked=!"ACTIVE".equals(user.get("status"))||d.exists("SELECT id FROM users WHERE id=? AND locked_until>?",id,d.now());
    require(!blocked,401,"INVALID_CREDENTIALS","이메일 또는 비밀번호를 확인해 주세요.");
    if(!passwords.matches(password,text(user,"password_hash"))){long count=number(user,"failed_login_count")+1;d.update("UPDATE users SET failed_login_count=?,locked_until=? WHERE id=?",count,count>=5?d.after(900):null,id);throw new ApiException(401,"INVALID_CREDENTIALS","이메일 또는 비밀번호를 확인해 주세요.");}
    require(user.get("temporary_password_expires_at")==null||d.exists("SELECT id FROM users WHERE id=? AND temporary_password_expires_at>?",id,d.now()),401,"TEMPORARY_PASSWORD_EXPIRED","임시 비밀번호가 만료되었습니다.");
    d.update("UPDATE users SET failed_login_count=0,locked_until=NULL,last_login_at=? WHERE id=?",d.now(),id);
    if(adminMfa&&"MANAGER".equals(user.get("role"))&&!flag(user.get("must_change_password"))){
      require(box.configured(),503,"ENCRYPTION_NOT_CONFIGURED","다중 인증 암호화 설정이 필요합니다.");
      String challenge=token(),kind=user.get("mfa_secret_ciphertext")==null?"ENROLL":"VERIFY";
      d.update("INSERT INTO auth_challenges(id,user_id,token_hash,kind,expires_at) VALUES(?,?,?,?,?)",d.id(),id,hash(challenge),kind,d.after(300));
      return Map.of("mfaRequired",true,"enrollmentRequired",kind.equals("ENROLL"),"challengeToken",challenge,"expiresIn",300);
    }
    return createSession(id,false);
  }

  // 세션 생성
  private Object createSession(long user,boolean mfaVerified){
    var sessions=d.rows("SELECT id FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL AND absolute_expires_at>? ORDER BY created_at,id",user,d.now());
    for(int i=0;i<=sessions.size()-5;i++)revokeSession(number(sessions.get(i),"id"));
    long id=d.id();d.update("INSERT INTO auth_sessions(id,user_id,created_at,last_used_at,absolute_expires_at,mfa_verified) VALUES(?,?,?,?,?,?)",id,user,d.now(),d.now(),d.after(sessionSeconds),mfaVerified);
    return issue(user,id);
  }

  // 세션 한도 내 토큰 발급
  private Object issue(long user,long session){
    var state=d.one("SELECT s.absolute_expires_at,u.must_change_password FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=?",session);
    long remaining=Duration.between(LocalDateTime.now(ZoneOffset.UTC),(LocalDateTime)state.get("absolute_expires_at")).getSeconds();
    require(remaining>0,401,"INVALID_TOKEN","세션이 만료되었습니다.");
    String access=token(),refresh=token();long accessSeconds=Math.min(900,remaining);
    d.update("INSERT INTO access_tokens(id,user_id,token_hash,expires_at,session_id) VALUES(?,?,?,?,?)",d.id(),user,hash(access),d.after(accessSeconds),session);
    d.update("INSERT INTO refresh_tokens(id,user_id,token_hash,expires_at,session_id) VALUES(?,?,?,?,?)",d.id(),user,hash(refresh),d.after(Math.min(604800,remaining)),session);
    return Map.of("accessToken",access,"refreshToken",refresh,"tokenType","Bearer","expiresIn",accessSeconds,"passwordChangeRequired",flag(state.get("must_change_password")));
  }

  @Transactional(noRollbackFor=ApiException.class,timeout=15)
  // 갱신 재사용 차단
  public Object refresh(String token){
    var refs=d.rows("SELECT user_id,session_id FROM refresh_tokens WHERE token_hash=?",hash(token));
    require(!refs.isEmpty()&&refs.getFirst().get("session_id")!=null,401,"INVALID_TOKEN","유효하지 않은 갱신 토큰입니다.");
    long user=number(refs.getFirst(),"user_id"),session=number(refs.getFirst(),"session_id");
    d.one("SELECT id FROM users WHERE id=? FOR UPDATE",user);
    var family=d.one("SELECT * FROM auth_sessions WHERE id=? FOR UPDATE",session);
    var row=d.one("SELECT * FROM refresh_tokens WHERE token_hash=? FOR UPDATE",hash(token));
    if(row.get("revoked_at")!=null){revokeSession(session);d.audit(user,"REFRESH_REUSE","SESSION",session);throw new ApiException(401,"TOKEN_REUSE_DETECTED","다시 로그인해 주세요.");}
    require(family.get("revoked_at")==null&&d.exists("SELECT s.id FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND s.absolute_expires_at>? AND s.last_used_at>? AND u.status='ACTIVE' AND u.deleted_at IS NULL",session,d.now(),d.after(-idleSeconds))&&d.exists("SELECT id FROM refresh_tokens WHERE id=? AND expires_at>?",row.get("id"),d.now()),401,"INVALID_TOKEN","세션이 만료되었거나 유효하지 않습니다.");
    if(adminMfa){var userRow=d.one("SELECT role,must_change_password FROM users WHERE id=?",user);require(!"MANAGER".equals(userRow.get("role"))||flag(userRow.get("must_change_password"))||flag(family.get("mfa_verified")),401,"MFA_REQUIRED","다중 인증이 필요합니다.");}
    d.update("UPDATE refresh_tokens SET revoked_at=? WHERE id=?",d.now(),row.get("id"));d.update("UPDATE auth_sessions SET last_used_at=? WHERE id=?",d.now(),session);return issue(user,session);
  }

  // 세션 인증 및 유휴 갱신
  public Actor authenticate(String token){
    var touchBefore = d.after(-60);
    var rows=d.rows("SELECT u.id,u.role,u.must_change_password,s.id AS session_id,s.mfa_verified,"
        + " CASE WHEN s.last_used_at<? THEN 1 ELSE 0 END AS needs_touch"
        + " FROM access_tokens t JOIN users u ON u.id=t.user_id JOIN auth_sessions s ON s.id=t.session_id WHERE t.token_hash=? AND t.revoked_at IS NULL AND t.expires_at>? AND s.revoked_at IS NULL AND s.absolute_expires_at>? AND s.last_used_at>? AND u.status='ACTIVE' AND u.deleted_at IS NULL",touchBefore,hash(token),d.now(),d.now(),d.after(-idleSeconds));
    if(rows.isEmpty())return null;var row=rows.getFirst();boolean change=flag(row.get("must_change_password"));
    if(adminMfa&&"MANAGER".equals(row.get("role"))&&!change&&!flag(row.get("mfa_verified")))return null;
    long session = number(row, "session_id");
    if (number(row, "needs_touch") != 0) {
      d.update("UPDATE auth_sessions SET last_used_at=? WHERE id=? AND last_used_at<?",
          d.now(), session, touchBefore);
    }
    return new Actor(number(row,"id"),text(row,"role"),change,session);
  }

  @Transactional
  // 전체 로그아웃
  public void logout(Actor actor){d.one("SELECT id FROM users WHERE id=? FOR UPDATE",actor.id());revoke(actor.id());}
  // 세션 계열 폐기
  private void revokeSession(long id){d.update("UPDATE auth_sessions SET revoked_at=? WHERE id=? AND revoked_at IS NULL",d.now(),id);d.update("UPDATE access_tokens SET revoked_at=? WHERE session_id=? AND revoked_at IS NULL",d.now(),id);d.update("UPDATE refresh_tokens SET revoked_at=? WHERE session_id=? AND revoked_at IS NULL",d.now(),id);}
  // 사용자 인증 폐기
  private void revoke(long id){d.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",d.now(),id);d.update("UPDATE refresh_tokens SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",d.now(),id);d.update("UPDATE access_tokens SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",d.now(),id);d.update("UPDATE auth_challenges SET used_at=? WHERE user_id=? AND used_at IS NULL",d.now(),id);}

  @Transactional(timeout=15)
  // 비밀번호 변경 및 제한 해제
  public void password(Actor actor,String oldPassword,String newPassword,String mfaCode){
    var user=d.one("SELECT * FROM users WHERE id=? FOR UPDATE",actor.id());reauthenticate(actor,user,oldPassword,mfaCode);
    validatePassword(newPassword);require(!oldPassword.equals(newPassword),400,"INVALID_PASSWORD","다른 비밀번호를 입력해 주세요.");
    d.update("UPDATE users SET password_hash=?,must_change_password=FALSE,temporary_password_expires_at=NULL,updated_at=? WHERE id=?",passwords.encode(newPassword),d.now(),actor.id());revoke(actor.id());d.audit(actor.id(),"CHANGE_PASSWORD","USER",actor.id());
  }

  // 민감 작업 재인증
  private void reauthenticate(Actor actor,Map<String,Object> user,String password,String mfaCode){
    require(password!=null&&password.getBytes(StandardCharsets.UTF_8).length<=72&&passwords.matches(password,text(user,"password_hash")),400,"INVALID_PASSWORD","현재 비밀번호가 올바르지 않습니다.");
    if(adminMfa&&actor.manager()&&!actor.mustChangePassword())require(verifyFactor(user,mfaCode),401,"INVALID_MFA","인증 코드를 확인해 주세요.");
  }

  // 비밀번호 검증
  public static void validatePassword(String password){require(password!=null&&password.length()>=12&&password.getBytes(StandardCharsets.UTF_8).length<=72,400,"INVALID_PASSWORD","비밀번호는 12자 이상, UTF-8 72바이트 이하여야 합니다.");}

  // 도전 토큰 검사
  private Map<String,Object> challenge(String token,String kind){
    var refs=d.rows("SELECT user_id FROM auth_challenges WHERE token_hash=?",hash(token));require(!refs.isEmpty(),401,"INVALID_CHALLENGE","인증 요청이 유효하지 않습니다.");
    var user=d.one("SELECT * FROM users WHERE id=? FOR UPDATE",refs.getFirst().get("user_id"));
    require("ACTIVE".equals(user.get("status"))&&user.get("deleted_at")==null&&!flag(user.get("must_change_password")),401,"INVALID_CHALLENGE","인증 요청이 유효하지 않습니다.");
    var challenge=d.one("SELECT * FROM auth_challenges WHERE token_hash=? FOR UPDATE",hash(token));
    require(kind.equals(challenge.get("kind"))&&challenge.get("used_at")==null&&number(challenge,"attempts")<5&&d.exists("SELECT id FROM auth_challenges WHERE id=? AND expires_at>?",challenge.get("id"),d.now()),401,"INVALID_CHALLENGE","인증 요청이 만료되었거나 유효하지 않습니다.");
    challenge.put("user",user);return challenge;
  }

  @Transactional(noRollbackFor=ApiException.class,timeout=15)
  // 인증 앱 등록 자료
  public Object enroll(String token){
    var challenge=challenge(token,"ENROLL");var user=(Map<String,Object>)challenge.get("user");require(user.get("mfa_secret_ciphertext")==null,409,"MFA_ALREADY_ENROLLED","이미 등록된 인증입니다.");
    String purpose="mfa:challenge:"+challenge.get("id"),secret;
    if(challenge.get("secret_ciphertext")==null){secret=Totp.secret();d.update("UPDATE auth_challenges SET secret_ciphertext=? WHERE id=?",box.sealText(secret,purpose),challenge.get("id"));}
    else secret=box.openText(text(challenge,"secret_ciphertext"),purpose);
    String label=java.net.URLEncoder.encode("KT Scholarship:"+user.get("email"),StandardCharsets.UTF_8).replace("+","%20");
    return Map.of("secret",secret,"otpauthUri","otpauth://totp/"+label+"?secret="+secret+"&issuer=KT%20Scholarship&algorithm=SHA1&digits=6&period=30");
  }

  @Transactional(noRollbackFor=ApiException.class,timeout=15)
  // 인증 앱 등록 확인
  public Object confirmEnrollment(String token,String code){
    var challenge=challenge(token,"ENROLL");var user=(Map<String,Object>)challenge.get("user");require(user.get("mfa_secret_ciphertext")==null&&challenge.get("secret_ciphertext")!=null,409,"INVALID_ENROLLMENT","등록 절차를 다시 시작해 주세요.");
    d.update("UPDATE auth_challenges SET attempts=attempts+1 WHERE id=?",challenge.get("id"));
    String secret=box.openText(text(challenge,"secret_ciphertext"),"mfa:challenge:"+challenge.get("id"));long step=Totp.verify(secret,code,-1,Instant.now().getEpochSecond());require(step>=0,401,"INVALID_MFA","인증 코드를 확인해 주세요.");
    long id=number(user,"id");d.update("UPDATE users SET mfa_secret_ciphertext=?,mfa_last_step=? WHERE id=?",box.sealText(secret,"mfa:user:"+id),step,id);
    d.update("UPDATE auth_challenges SET used_at=? WHERE user_id=? AND used_at IS NULL",d.now(),id);
    List<String> recovery=recoveryCodes(id);var result=new LinkedHashMap<String,Object>((Map<String,Object>)createSession(id,true));result.put("recoveryCodes",recovery);d.audit(id,"ENROLL_MFA","USER",id);return result;
  }

  // 인증 요소 확인
  private boolean verifyFactor(Map<String,Object> user,String code){
    if(code==null||user.get("mfa_secret_ciphertext")==null)return false;long id=number(user,"id");
    if(code.startsWith("R-")){
      var rows=d.rows("SELECT id FROM mfa_recovery_codes WHERE user_id=? AND code_hash=? AND used_at IS NULL FOR UPDATE",id,hash(code));if(rows.isEmpty())return false;
      d.update("UPDATE mfa_recovery_codes SET used_at=? WHERE id=?",d.now(),rows.getFirst().get("id"));return true;
    }
    String secret=box.openText(text(user,"mfa_secret_ciphertext"),"mfa:user:"+id);long step=Totp.verify(secret,code,number(user,"mfa_last_step"),Instant.now().getEpochSecond());if(step<0)return false;
    d.update("UPDATE users SET mfa_last_step=? WHERE id=?",step,id);return true;
  }

  @Transactional(noRollbackFor=ApiException.class,timeout=15)
  // 등록 인증 확인
  public Object verifyMfa(String token,String code){
    var challenge=challenge(token,"VERIFY");var user=(Map<String,Object>)challenge.get("user");d.update("UPDATE auth_challenges SET attempts=attempts+1 WHERE id=?",challenge.get("id"));
    require(verifyFactor(user,code),401,"INVALID_MFA","인증 코드를 확인해 주세요.");d.update("UPDATE auth_challenges SET used_at=? WHERE id=?",d.now(),challenge.get("id"));return createSession(number(user,"id"),true);
  }

  // 일회 복구 코드
  private List<String> recoveryCodes(long user){
    d.update("DELETE FROM mfa_recovery_codes WHERE user_id=?",user);var codes=new ArrayList<String>();for(int i=0;i<8;i++){String code="R-"+token();codes.add(code);d.update("INSERT INTO mfa_recovery_codes(id,user_id,code_hash) VALUES(?,?,?)",d.id(),user,hash(code));}return codes;
  }

  @Transactional(timeout=15)
  // 복구 코드 교체
  public Object replaceRecoveryCodes(Actor actor,String password,String code){var user=d.one("SELECT * FROM users WHERE id=? FOR UPDATE",actor.id());reauthenticate(actor,user,password,code);require(user.get("mfa_secret_ciphertext")!=null,409,"MFA_REQUIRED","인증 앱 등록이 필요합니다.");var result=recoveryCodes(actor.id());d.audit(actor.id(),"REPLACE_RECOVERY_CODES","USER",actor.id());return Map.of("recoveryCodes",result);}

  @Transactional(timeout=15)
  // 인증 앱 재등록 전환
  public void resetMfa(Actor actor,String password,String code){var user=d.one("SELECT * FROM users WHERE id=? FOR UPDATE",actor.id());reauthenticate(actor,user,password,code);d.update("UPDATE users SET mfa_secret_ciphertext=NULL,mfa_last_step=-1 WHERE id=?",actor.id());d.update("DELETE FROM mfa_recovery_codes WHERE user_id=?",actor.id());revoke(actor.id());d.audit(actor.id(),"RESET_MFA","USER",actor.id());}

  @Transactional(timeout=15)
  // 담당 범위 내 임시 비밀번호
  public Object resetScholar(Actor actor,long id,String password,String code){
    var operator=d.one("SELECT * FROM users WHERE id=? FOR UPDATE",actor.id());reauthenticate(actor,operator,password,code);
    var scholar=d.one("SELECT c.program_id,p.cohort_id FROM scholar_profiles p JOIN cohorts c ON c.id=p.cohort_id JOIN users u ON u.id=p.user_id WHERE p.user_id=? AND u.role='SCHOLAR'",id);workflow.scope(actor,number(scholar,"program_id"),number(scholar,"cohort_id"));
    String temporary=token();d.update("UPDATE users SET password_hash=?,must_change_password=TRUE,temporary_password_expires_at=?,failed_login_count=0,locked_until=NULL WHERE id=?",passwords.encode(temporary),d.after(86400),id);revoke(id);d.audit(actor.id(),"RESET_SCHOLAR_PASSWORD","USER",id);return Map.of("temporaryPassword",temporary,"expiresIn",86400);
  }

  // 본인 세션 목록
  public Object sessions(Actor actor){return d.rows("SELECT id,created_at,last_used_at,absolute_expires_at,revoked_at FROM auth_sessions WHERE user_id=? ORDER BY created_at DESC LIMIT 50",actor.id());}
  @Transactional(timeout=15)
  // 본인 세션 폐기
  public void endSession(Actor actor,long session,String password,String code){var user=d.one("SELECT * FROM users WHERE id=? FOR UPDATE",actor.id());reauthenticate(actor,user,password,code);require(d.exists("SELECT id FROM auth_sessions WHERE id=? AND user_id=?",session,actor.id()),404,"NOT_FOUND","세션을 찾을 수 없습니다.");revokeSession(session);d.audit(actor.id(),"REVOKE_SESSION","SESSION",session);}
}

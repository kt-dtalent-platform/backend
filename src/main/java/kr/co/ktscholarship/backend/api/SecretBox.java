package kr.co.ktscholarship.backend.api;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
// 인증 암호화
public class SecretBox {
  // 키 목록
  private final Map<String,byte[]> keys=new HashMap<>();
  // 활성 키
  private final String active;
  // 보안 난수
  private final SecureRandom random=new SecureRandom();

  // 암호화 설정
  public SecretBox(JsonMapper json,@Value("${app.crypto.keys:{}}")String encoded,@Value("${app.crypto.active-key:v1}")String active) {
    this.active=active;
    Map<?,?> configured=json.readValue(encoded,Map.class);
    configured.forEach((id,value)-> {
      if(!id.toString().matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("INVALID_KEY_ID");
      byte[] key=Base64.getDecoder().decode(value.toString());
      if(key.length!=32) throw new IllegalArgumentException("AES_256_KEY_REQUIRED");
      keys.put(id.toString(),key);
    });
    if(!keys.isEmpty()&&!keys.containsKey(active)) throw new IllegalArgumentException("ACTIVE_KEY_MISSING");
  }

  // 키 준비 상태
  public boolean configured() {return keys.containsKey(active);}

  // 암호문 생성
  public byte[] seal(byte[] plain,String purpose) {
    if(!configured()) throw new ApiException(503,"ENCRYPTION_NOT_CONFIGURED","암호화 설정이 필요합니다.");
    try {
      byte[] nonce=new byte[12];random.nextBytes(nonce);
      var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(keys.get(active),"AES"),new GCMParameterSpec(128,nonce));
      cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
      byte[] payload=cipher.doFinal(plain),id=active.getBytes(StandardCharsets.US_ASCII);
      return ByteBuffer.allocate(5+id.length+12+payload.length).put(new byte[]{'K','T','B','1'}).put((byte)id.length).put(id).put(nonce).put(payload).array();
    }catch(GeneralSecurityException e){throw new IllegalStateException("ENCRYPTION_FAILED",e);}
  }

  // 암호문 검증
  public byte[] open(byte[] encoded,String purpose) {
    try {
      var buffer=ByteBuffer.wrap(encoded);byte[] magic=new byte[4];buffer.get(magic);
      if(!Arrays.equals(magic,new byte[]{'K','T','B','1'})) throw new GeneralSecurityException();
      int length=buffer.get()&255;
      if(length<1||length>32||buffer.remaining()<length+28) throw new GeneralSecurityException();
      byte[] id=new byte[length],nonce=new byte[12];buffer.get(id);buffer.get(nonce);
      byte[] key=keys.get(new String(id,StandardCharsets.US_ASCII));
      if(key==null) throw new GeneralSecurityException();
      byte[] payload=new byte[buffer.remaining()];buffer.get(payload);
      var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
      cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return cipher.doFinal(payload);
    }catch(GeneralSecurityException|RuntimeException e){throw new ApiException(503,"INTEGRITY_CHECK_FAILED","저장 자료의 무결성을 확인할 수 없습니다.");}
  }

  // 비밀 문자열 암호화
  public String sealText(String value,String purpose) {return Base64.getEncoder().encodeToString(seal(value.getBytes(StandardCharsets.UTF_8),purpose));}

  // 비밀 문자열 복호화
  public String openText(String value,String purpose) {return new String(open(Base64.getDecoder().decode(value),purpose),StandardCharsets.UTF_8);}
}

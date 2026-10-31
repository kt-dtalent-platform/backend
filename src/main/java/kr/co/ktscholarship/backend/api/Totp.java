package kr.co.ktscholarship.backend.api;

import java.nio.ByteBuffer;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// 시간 기반 인증 코드
public final class Totp {
  // Base32 문자
  private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  // 직접 생성 제한
  private Totp(){}

  // 인증 비밀 생성
  public static String secret(){byte[] bytes=new byte[20];new SecureRandom().nextBytes(bytes);return encode(bytes);}

  // Base32 변환
  static String encode(byte[] bytes){StringBuilder out=new StringBuilder();int buffer=0,bits=0;for(byte b:bytes){buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5){bits-=5;out.append(ALPHABET.charAt((buffer>>bits)&31));}}if(bits>0)out.append(ALPHABET.charAt((buffer<<(5-bits))&31));return out.toString();}

  // Base32 복원
  private static byte[] decode(String value){var out=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;for(char c:value.toCharArray()){int n=ALPHABET.indexOf(c);if(n<0)throw new IllegalArgumentException("INVALID_TOTP_SECRET");buffer=(buffer<<5)|n;bits+=5;if(bits>=8){bits-=8;out.write((buffer>>bits)&255);}}return out.toByteArray();}

  // HOTP 계산
  public static String code(String secret,long step,int digits){
    try{var mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(decode(secret),"HmacSHA1"));byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int offset=hash[hash.length-1]&15;int binary=((hash[offset]&127)<<24)|((hash[offset+1]&255)<<16)|((hash[offset+2]&255)<<8)|(hash[offset+3]&255);int divisor=digits==8?100000000:1000000;return String.format(java.util.Locale.ROOT,"%0"+digits+"d",binary%divisor);}catch(GeneralSecurityException e){throw new IllegalStateException(e);}
  }

  // 재사용 방지 검증
  public static long verify(String secret,String code,long lastStep,long epochSeconds){
    if(code==null||!code.matches("[0-9]{6}"))return -1;
    long current=epochSeconds/30;
    for(long step=current-1;step<=current+1;step++)if(step>lastStep&&MessageDigest.isEqual(code(secret,step,6).getBytes(java.nio.charset.StandardCharsets.US_ASCII),code.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))return step;
    return -1;
  }
}

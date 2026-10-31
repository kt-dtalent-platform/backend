package kr.co.ktscholarship.backend.api;

import java.net.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
// 신뢰 프록시 주소
public class ClientAddress {
  // 신뢰 대역
  private final List<Range> trusted=new ArrayList<>();
  // 주소 대역
  private record Range(byte[] address,int bits) {
    // 대역 포함 여부
    boolean contains(byte[] other){
      if(address.length!=other.length)return false;
      for(int i=0;i<address.length;i++){int remaining=Math.min(8,Math.max(0,bits-i*8));int mask=remaining==0?0:0xff<<(8-remaining);if((address[i]&mask)!=(other[i]&mask))return false;}return true;
    }
  }

  // 신뢰 대역 설정
  public ClientAddress(@Value("${app.trusted-proxies:}")String configured){
    if(configured.isBlank())return;
    for(String entry:configured.split(",")){
      String[] parts=entry.trim().split("/",-1);byte[] address=parse(parts[0]);int bits=parts.length==1?address.length*8:Integer.parseInt(parts[1]);
      if(parts.length>2||bits<1||bits>address.length*8)throw new IllegalArgumentException("INVALID_PROXY_RANGE");trusted.add(new Range(address,bits));
    }
  }

  // IP 리터럴 검사
  private static byte[] parse(String value){
    if(value==null||value.length()>45||!value.matches("[0-9A-Fa-f:.]+")||(!value.contains(":")&&!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))throw new IllegalArgumentException("INVALID_CLIENT_ADDRESS");
    try{return InetAddress.getByName(value).getAddress();}catch(UnknownHostException e){throw new IllegalArgumentException("INVALID_CLIENT_ADDRESS");}
  }

  // 신뢰 주소 검사
  private boolean trusted(String value){byte[] address=parse(value);return trusted.stream().anyMatch(range->range.contains(address));}

  // 실제 접속 주소
  public String resolve(HttpServletRequest request){
    String current=request.getRemoteAddr();parse(current);
    String forwarded=request.getHeader("X-Forwarded-For");
    if(!trusted(current)||forwarded==null||forwarded.isBlank())return current;
    if(forwarded.length()>2048)throw new ApiException(400,"INVALID_PROXY_HEADER","접속 주소를 확인할 수 없습니다.");
    String[] chain=forwarded.split(",",-1);if(chain.length>20)throw new ApiException(400,"INVALID_PROXY_HEADER","접속 주소를 확인할 수 없습니다.");
    try{for(int i=chain.length-1;i>=0&&trusted(current);i--){current=chain[i].trim();parse(current);}return InetAddress.getByAddress(parse(current)).getHostAddress();}
    catch(IllegalArgumentException|UnknownHostException e){throw new ApiException(400,"INVALID_PROXY_HEADER","접속 주소를 확인할 수 없습니다.");}
  }
}

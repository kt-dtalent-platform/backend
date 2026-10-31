package kr.co.ktscholarship.backend.api;

import java.io.*;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
// 요청 추적 및 본문 제한
public class RequestTraceFilter extends OncePerRequestFilter {
  // 주소 검증
  private final ClientAddress address;
  // 응답 직렬화
  private final Database d;

  // 필터 설정
  public RequestTraceFilter(ClientAddress address,Database d){this.address=address;this.d=d;}

  // 요청 문맥 처리
  protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    String id=UUID.randomUUID().toString();long start=System.nanoTime();MDC.put("requestId",id);response.setHeader("X-Request-Id",id);
    try {
      AuditContext.set(new AuditContext.Value(id,address.resolve(request)));
      String content=Objects.toString(request.getContentType(),"");
      if(content.startsWith("application/json")){
        byte[] data=request.getInputStream().readNBytes(65537);
        if(data.length>65536)throw new ApiException(413,"REQUEST_TOO_LARGE","요청 본문이 너무 큽니다.");
        request=new BufferedRequest(request,data);
      }
      chain.doFilter(request,response);
    }catch(ApiException error){response.setStatus(error.status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(d.encode(ApiAdvice.envelope(null,error.code,error.getMessage(),request.getRequestURI())));}
    finally {
      LoggerFactory.getLogger(RequestTraceFilter.class).info("request method={} path={} status={} durationMs={}",request.getMethod(),request.getRequestURI(),response.getStatus(),(System.nanoTime()-start)/1000000);
      AuditContext.clear();MDC.remove("requestId");MDC.remove("userId");
    }
  }

  // 제한 본문 재사용
  private static class BufferedRequest extends HttpServletRequestWrapper {
    // 본문
    private final byte[] body;
    // 요청 래퍼
    BufferedRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
    // 본문 스트림
    public ServletInputStream getInputStream(){
      var input=new ByteArrayInputStream(body);
      return new ServletInputStream(){
        public int read(){return input.read();}
        public int read(byte[] buffer,int offset,int length){return input.read(buffer,offset,length);}
        public boolean isFinished(){return input.available()==0;}
        public boolean isReady(){return true;}
        public void setReadListener(ReadListener listener){throw new IllegalStateException("SYNCHRONOUS_BODY_ONLY");}
      };
    }
    // 본문 문자 입력
    public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
  }
}

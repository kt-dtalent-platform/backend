package kr.co.ktscholarship.backend.api;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
// 외부 분석 연결
public class OcrClient {
  // JSON 변환
  private final Database d;
  // 분석 주소
  private final String url;
  // 인증키
  private final String key;
  // 전체 기한
  private final int timeout;
  // 동시 요청 제한
  private final Semaphore slots=new Semaphore(3);
  // HTTP 연결
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

  // 분석 설정
  public OcrClient(Database d,@Value("${app.ocr.url:}")String url,@Value("${app.ocr.key:}")String key,@Value("${app.ocr.timeout-ms:25000}")int timeout) {
    if(timeout<100||timeout>30000)throw new IllegalArgumentException("INVALID_OCR_TIMEOUT");this.d=d;this.url=url;this.key=key;this.timeout=timeout;
  }

  // 문서 값 추출
  public Map<String,Object> extract(byte[] bytes,String contentType) throws Exception {
    if(url.isBlank())throw new IllegalStateException("OCR_NOT_CONFIGURED");
    URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null)throw new IllegalStateException("INVALID_OCR_URL");
    if(!slots.tryAcquire())throw new ApiException(429,"OCR_BUSY","분석 요청이 많습니다.");
    try {
      var request=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout)).header("Content-Type","application/json").header("Authorization","Bearer "+key)
          .POST(HttpRequest.BodyPublishers.ofString(d.encode(Map.of("contentType",contentType,"fileBase64",Base64.getEncoder().encodeToString(bytes))))).build();
      return d.object(receive(client,request,timeout,1024*1024));
    }finally{slots.release();}
  }

  // 전체 응답 기한
  static byte[] receive(HttpClient client,HttpRequest request,int timeout,int maxBytes) throws Exception {
    var future=client.sendAsync(request,info->new LimitedBody(maxBytes));
    try {
      var response=future.get(timeout,TimeUnit.MILLISECONDS);
      if(response.statusCode()!=200)throw new IllegalStateException("OCR_PROVIDER_ERROR");return response.body();
    }catch(Exception e){future.cancel(true);throw e;}
  }

  // 본문 용량 제한
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    // 본문 완료
    private final CompletableFuture<byte[]> complete=new CompletableFuture<>();
    // 본문 버퍼
    private final ByteArrayOutputStream output=new ByteArrayOutputStream();
    // 최대 크기
    private final int maximum;
    // 수신 구독
    private Flow.Subscription subscription;

    // 본문 설정
    LimitedBody(int maximum){this.maximum=maximum;}
    // 완료 결과
    public CompletionStage<byte[]> getBody(){return complete;}
    // 수신 시작
    public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
    // 본문 수신
    public void onNext(List<ByteBuffer> buffers){
      for(ByteBuffer buffer:buffers){if(buffer.remaining()>maximum-output.size()){subscription.cancel();complete.completeExceptionally(new IllegalStateException("OCR_RESPONSE_TOO_LARGE"));return;}
        byte[] part=new byte[buffer.remaining()];buffer.get(part);output.writeBytes(part);}
      subscription.request(1);
    }
    // 수신 오류
    public void onError(Throwable error){complete.completeExceptionally(error);}
    // 수신 완료
    public void onComplete(){complete.complete(output.toByteArray());}
  }
}

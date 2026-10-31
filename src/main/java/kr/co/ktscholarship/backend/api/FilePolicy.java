package kr.co.ktscholarship.backend.api;

import static kr.co.ktscholarship.backend.api.ApiException.require;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
// 문서 구조 및 검역
public class FilePolicy {
  // 최대 파일 크기
  public static final int MAX_BYTES=10*1024*1024;
  // 검증 동시 실행 한도
  private final Semaphore slots=new Semaphore(2);
  // 검역 주소
  private final String host;
  // 검역 포트
  private final int port;
  // 검역 필수 여부
  private final boolean required;

  // 검역 설정
  public FilePolicy(@Value("${app.antivirus.host:}")String host,@Value("${app.antivirus.port:3310}")int port,@Value("${app.antivirus.required:false}")boolean required) {this.host=host;this.port=port;this.required=required;}

  // 검증 결과
  public record Validated(String contentType,String scanStatus) {}

  // 업로드 내용 검증
  public Validated validate(byte[] data,String filename) {
    require(data.length>0&&data.length<=MAX_BYTES,400,"INVALID_FILE","파일 크기를 확인해 주세요.");
    require(slots.tryAcquire(),429,"FILE_PROCESSING_BUSY","파일 처리 요청이 많습니다.");
    try {
      String extension=filename.contains(".")?filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
      String mime;
      if(extension.equals("pdf")) {pdf(data);mime="application/pdf";}
      else if(Set.of("png","jpg","jpeg").contains(extension)) {mime=image(data,extension);}
      else throw new ApiException(400,"INVALID_FILE","PDF, PNG, JPEG 파일만 허용됩니다.");
      return new Validated(mime,scan(data));
    }finally{slots.release();}
  }

  // PDF 구조 검증
  private void pdf(byte[] data) {
    try(var document=Loader.loadPDF(data)) {
      require(!document.isEncrypted()&&document.getNumberOfPages()>0&&document.getNumberOfPages()<=30,400,"INVALID_PDF","암호화되지 않은 30쪽 이하 PDF가 필요합니다.");
      var seen=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());
      var pending=new ArrayDeque<COSBase>();pending.add(document.getDocumentCatalog().getCOSObject());
      Set<String> forbidden=Set.of("OpenAction","AA","JS","JavaScript","Launch","EmbeddedFiles","EmbeddedFile","RichMedia","XFA");
      while(!pending.isEmpty()) {
        COSBase item=pending.removeFirst();if(!seen.add(item))continue;
        require(seen.size()<=50000,400,"INVALID_PDF","PDF 구조가 너무 복잡합니다.");
        if(item instanceof COSObject object) {if(object.getObject()!=null)pending.add(object.getObject());}
        else if(item instanceof COSDictionary dictionary) {
          for(COSName key:dictionary.keySet()) {require(!forbidden.contains(key.getName()),400,"UNSAFE_PDF","실행 동작이나 첨부 자료가 있는 PDF는 허용되지 않습니다.");COSBase child=dictionary.getItem(key);if(child!=null)pending.add(child);}
        }else if(item instanceof COSArray array) {for(COSBase child:array)if(child!=null)pending.add(child);}
        else if(item instanceof COSName name) require(!forbidden.contains(name.getName()),400,"UNSAFE_PDF","실행 동작이 있는 PDF는 허용되지 않습니다.");
      }
    }catch(ApiException e){throw e;}catch(IOException|RuntimeException e){throw new ApiException(400,"INVALID_PDF","유효한 PDF 문서가 아닙니다.");}
  }

  // 이미지 구조 검증
  private String image(byte[] data,String extension) {
    try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
      var readers=ImageIO.getImageReaders(input);require(readers.hasNext(),400,"INVALID_IMAGE","이미지 형식을 확인해 주세요.");
      var reader=readers.next();
      try {
        reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
        require(extension.equals("png")?format.equals("png"):Set.of("jpeg","jpg").contains(format),400,"INVALID_IMAGE","이미지 확장자가 내용과 다릅니다.");
        int width=reader.getWidth(0),height=reader.getHeight(0);
        require(width>0&&height>0&&(long)width*height<=16000000L,400,"INVALID_IMAGE","이미지 해상도가 허용 범위를 초과했습니다.");
        BufferedImage decoded=reader.read(0);require(decoded!=null,400,"INVALID_IMAGE","이미지를 읽을 수 없습니다.");decoded.flush();
        return format.equals("png")?"image/png":"image/jpeg";
      }finally{reader.dispose();}
    }catch(ApiException e){throw e;}catch(IOException|RuntimeException e){throw new ApiException(400,"INVALID_IMAGE","유효한 이미지가 아닙니다.");}
  }

  // 악성 파일 검사
  private String scan(byte[] data) {
    if(host.isBlank()) {require(!required,503,"SCANNER_UNAVAILABLE","파일 검역 연결이 필요합니다.");return "BYPASSED";}
    try(var socket=new Socket()) {
      socket.connect(new InetSocketAddress(host,port),1000);socket.setSoTimeout(10000);
      var output=new DataOutputStream(socket.getOutputStream());output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
      for(int offset=0;offset<data.length;offset+=8192){int length=Math.min(8192,data.length-offset);output.writeInt(length);output.write(data,offset,length);}output.writeInt(0);output.flush();
      var response=new ByteArrayOutputStream();int value;while((value=socket.getInputStream().read())!=-1&&value!=0){require(response.size()<4096,503,"SCANNER_UNAVAILABLE","검역 응답을 확인할 수 없습니다.");response.write(value);}
      String result=response.toString(StandardCharsets.UTF_8);
      require(!result.endsWith(" FOUND"),422,"UNSAFE_FILE","검역에서 차단된 파일입니다.");
      require(result.equals("stream: OK"),503,"SCANNER_UNAVAILABLE","파일 검역을 완료할 수 없습니다.");return "CLEAN";
    }catch(ApiException e){throw e;}catch(IOException e){throw new ApiException(503,"SCANNER_UNAVAILABLE","파일 검역을 완료할 수 없습니다.");}
  }
}

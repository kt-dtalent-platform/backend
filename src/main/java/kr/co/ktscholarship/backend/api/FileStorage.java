package kr.co.ktscholarship.backend.api;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
// 비공개 파일 저장소
public class FileStorage {
  // 저장 경로
  private final Path root;
  // 암호화
  private final SecretBox box;
  // 운영 모드
  private final boolean production;
  // 무결성 오류
  private final io.micrometer.core.instrument.Counter failures;

  // 저장소 설정
  public FileStorage(SecretBox box,MeterRegistry metrics,@Value("${app.storage-path:.data/files}")String root,@Value("${app.production:false}")boolean production) {
    this.box=box;this.root=Path.of(root).toAbsolutePath().normalize();this.production=production;
    failures=metrics.counter("storage.integrity.failures");
  }

  // 저장 결과
  public record Stored(String key,String format) {}

  // 안전 경로
  public Path path(String key) {
    ApiException.require(key!=null&&key.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"),500,"STORAGE_ERROR","저장 경로를 확인할 수 없습니다.");
    Path path=root.resolve(key).normalize();
    ApiException.require(path.startsWith(root)&&!Files.isSymbolicLink(path),500,"STORAGE_ERROR","저장 경로를 확인할 수 없습니다.");return path;
  }

  // 원본 저장
  public Stored write(byte[] plain) throws IOException {
    Files.createDirectories(root);String key=UUID.randomUUID().toString();Path path=path(key);
    byte[] encoded=box.configured()?box.seal(plain,"file:"+key):plain;
    if(production&&!box.configured())throw new ApiException(503,"ENCRYPTION_NOT_CONFIGURED","저장소 암호화 설정이 필요합니다.");
    try {
      if(Files.getFileStore(root).supportsFileAttributeView("posix"))Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      else Files.createFile(path);
      Files.write(path,encoded,StandardOpenOption.WRITE);
      return new Stored(key,box.configured()?"AES_GCM_V1":"PLAIN");
    }catch(IOException e){try{Files.deleteIfExists(path);}catch(IOException cleanup){org.slf4j.LoggerFactory.getLogger(FileStorage.class).error("storage_write_cleanup_failed");}throw e;}
  }

  // 원본 무결성 검증
  public byte[] read(Map<String,Object> file) throws IOException {
    ApiException.require("UPLOADED".equals(file.get("upload_status")),404,"FILE_NOT_FOUND","파일을 찾을 수 없습니다.");
    if(production)ApiException.require("CLEAN".equals(file.get("scan_status"))&&"AES_GCM_V1".equals(file.get("storage_format")),503,"FILE_REVIEW_REQUIRED","기존 파일의 검역 및 암호화가 필요합니다.");
    try(var input=Files.newInputStream(path(Database.text(file,"s3_key")),LinkOption.NOFOLLOW_LINKS)) {
      byte[] encoded=input.readNBytes(FilePolicy.MAX_BYTES+1024);
      if(input.read()!=-1)throw new ApiException(503,"INTEGRITY_CHECK_FAILED","저장 파일 크기가 올바르지 않습니다.");
      byte[] plain="AES_GCM_V1".equals(file.get("storage_format"))?box.open(encoded,"file:"+file.get("s3_key")):encoded;
      boolean valid=plain.length==Database.number(file,"size_bytes")&&MessageDigest.isEqual(checksum(plain).getBytes(java.nio.charset.StandardCharsets.US_ASCII),Database.text(file,"checksum").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      ApiException.require(valid,503,"INTEGRITY_CHECK_FAILED","저장 파일의 무결성을 확인할 수 없습니다.");return plain;
    }catch(NoSuchFileException e){throw new ApiException(404,"FILE_NOT_FOUND","파일을 찾을 수 없습니다.");}
    catch(ApiException e){failures.increment();org.slf4j.LoggerFactory.getLogger(FileStorage.class).error("storage_integrity_failure fileId={}",file.get("id"));throw e;}
  }

  // 물리 파일 삭제
  public void remove(String key) throws IOException {Files.deleteIfExists(path(key));}

  // 원본 해시
  public static String checksum(byte[] data) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }

  // 고아 파일 후보
  public List<String> orphanCandidates() throws IOException {
    if(!Files.isDirectory(root))return List.of();
    try(var paths=Files.list(root)) {
      Instant cutoff=Instant.now().minus(Duration.ofHours(24));
      return paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}"))
          .filter(p->{try{return Files.getLastModifiedTime(p,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);}catch(IOException e){return false;}}).limit(1000).map(p->p.getFileName().toString()).toList();
    }
  }
}

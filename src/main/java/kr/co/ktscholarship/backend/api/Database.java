package kr.co.ktscholarship.backend.api;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
// DB 접근
public class Database {
  // SQL 실행
  private final JdbcTemplate jdbc;
  // JSON 변환
  public final JsonMapper json;
  // 비공개 필드
  private static final Set<String> PRIVATE_FIELDS =
      Set.of("password_hash", "s3_key", "token_hash", "passwordHash", "s3Key", "tokenHash", "mfa_secret_ciphertext", "secret_ciphertext", "code_hash", "mfaSecretCiphertext", "secretCiphertext", "codeHash");
  // JSON 필드
  private static final Set<String> JSON_FIELDS =
      Set.of(
          "answers",
          "snapshot",
          "entered_values",
          "extracted_values",
          "comparisons",
          "default_fields",
          "options",
          "validation");
  // 원본 항목 키
  private static final Set<String> ORIGINAL_KEYS =
      Set.of(
          "answers",
          "entered_values",
          "extracted_values",
          "comparisons",
          "default_fields",
          "options",
          "validation",
          "enteredValues",
          "extractedValues",
          "defaultFields");
  // 보안 난수
  private final SecureRandom random = new SecureRandom();

  // 의존성 주입
  public Database(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  // 53비트 식별자
  public long id() {
    return (random.nextLong() & ((1L << 53) - 1)) | 1;
  }

  // 현재 시각
  public Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  // 만료 시각
  public Timestamp after(long seconds) {
    return Timestamp.from(Instant.now().plusSeconds(seconds));
  }

  // UTC 매개변수
  private Object[] parameters(Object[] args) {
    Object[] converted = args;
    for (int i = 0; i < args.length; i++) {
      if (args[i] instanceof Timestamp timestamp) {
        if (converted == args) converted = args.clone();
        converted[i] =
            java.time.LocalDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
      }
    }
    return converted;
  }

  // 단건 변경
  public int update(String sql, Object... args) {
    return jdbc.update(sql, parameters(args));
  }

  // 일괄 저장
  public void batch(String sql, List<Object[]> args) {
    jdbc.batchUpdate(sql, args.stream().map(this::parameters).toList());
  }

  // 목록 조회
  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        rs -> {
          var metadata = rs.getMetaData();
          int columns = metadata.getColumnCount();
          String[] names = new String[columns];
          boolean[] timestamps = new boolean[columns];
          // 조회 열 정보 재사용
          for (int i = 0; i < columns; i++) {
            names[i] = metadata.getColumnLabel(i + 1).toLowerCase(Locale.ROOT);
            timestamps[i] = metadata.getColumnType(i + 1) == java.sql.Types.TIMESTAMP;
          }
          var result = new ArrayList<Map<String, Object>>();
          while (rs.next()) {
            var row = new LinkedHashMap<String, Object>(columns);
            for (int i = 0; i < columns; i++) {
              row.put(
                  names[i],
                  timestamps[i]
                      ? rs.getObject(i + 1, java.time.LocalDateTime.class)
                      : rs.getObject(i + 1));
            }
            result.add(row);
          }
          return result;
        },
        parameters(args));
  }

  // 단건 조회
  public Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "대상을 찾을 수 없습니다.");
    return rows.getFirst();
  }

  // 존재 확인
  public boolean exists(String sql, Object... args) {
    return Boolean.TRUE.equals(
        jdbc.query(
            "SELECT EXISTS(" + sql + ")",
            (org.springframework.jdbc.core.ResultSetExtractor<Boolean>)
                rs -> rs.next() && rs.getBoolean(1),
            parameters(args)));
  }

  // 조회 페이지
  public record Page(List<Map<String, Object>> items, Long nextCursor, boolean hasMore) {}

  // 커서 조회
  public Page page(
      String sql, String cursorColumn, String cursorKey, long after, int size, Object... args) {
    ApiException.require(
        after >= 0 && size >= 1 && size <= 200,
        400,
        "INVALID_PAGE",
        "after는 0 이상, size는 1~200이어야 합니다.");
    var params = new ArrayList<Object>(Arrays.asList(args));
    params.add(after);
    params.add(size + 1);
    var found =
        rows(
            sql + " AND " + cursorColumn + ">? ORDER BY " + cursorColumn + " LIMIT ?",
            params.toArray());
    boolean more = found.size() > size;
    var items = more ? new ArrayList<>(found.subList(0, size)) : found;
    return new Page(items, more ? number(items.getLast(), cursorKey) : null, more);
  }

  // JSON 직렬화
  public String encode(Object value) {
    return json.writeValueAsString(value);
  }

  @SuppressWarnings("unchecked")
  // JSON 객체 변환
  public Map<String, Object> object(Object value) {
    if (value instanceof Map<?, ?>) return (Map<String, Object>) value;
    return (Map<String, Object>) decode(value);
  }

  // JSON 역직렬화
  private Object decode(Object value) {
    String s =
        value instanceof byte[] b
            ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
            : String.valueOf(value);
    Object parsed = json.readValue(s, Object.class);
    // H2 JSON 문자열 호환
    if (parsed instanceof String str) parsed = json.readValue(str, Object.class);
    return parsed;
  }

  // 응답 데이터 변환
  public Object output(Object value) {
    return output(value, false);
  }

  // 응답 데이터 변환
  private Object output(Object value, boolean preserveKeys) {
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>(map.size());
      map.forEach(
          (k, v) -> {
            String key = k.toString();
            Object actual = v;
            if (PRIVATE_FIELDS.contains(key)) return;
            if ((v instanceof String || v instanceof byte[]) && JSON_FIELDS.contains(key)) {
              actual = decode(v);
            }
            String responseKey = key;
            if (!preserveKeys && key.indexOf('_') >= 0) {
              StringBuilder camel = new StringBuilder(key.length());
              boolean upper = false;
              for (char c : key.toCharArray()) {
                if (c == '_') upper = true;
                else {
                  camel.append(upper ? Character.toUpperCase(c) : c);
                  upper = false;
                }
              }
              responseKey = camel.toString();
            }
            boolean childPreserve = preserveKeys || ORIGINAL_KEYS.contains(key);
            result.put(responseKey, output(actual, childPreserve));
          });
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(v -> output(v, preserveKeys)).toList();
    if (value instanceof java.time.LocalDateTime t)
      return t.toInstant(java.time.ZoneOffset.UTC).toString();
    if (value instanceof Timestamp t) return t.toInstant().toString();
    return value;
  }

  // 감사 기록
  public void audit(long actor, String action, String type, long target) {
    audit(actor,action,type,target,null);
  }

  // 접속 문맥 및 처리 건수
  public void audit(long actor,String action,String type,long target,Integer count){
    var context=AuditContext.current();
    update("INSERT INTO audit_logs(id,actor_id,action,target_type,target_id,row_count,ip_address,request_id) VALUES(?,?,?,?,?,?,?,?)",id(),actor,action,type,target,count,context==null?null:context.address(),context==null?null:context.requestId());
  }

  // 숫자 변환
  public static long number(Map<String, Object> m, String key) {
    return ((Number) m.get(key)).longValue();
  }

  // 문자 변환
  public static String text(Map<String, Object> m, String key) {
    return Objects.toString(m.get(key), "");
  }
}

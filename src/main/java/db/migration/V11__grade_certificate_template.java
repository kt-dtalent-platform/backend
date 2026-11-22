package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 성적증명서 요청 템플릿(GRADE_CERTIFICATE)을 넣는다. 개발 서버·운영에 템플릿이 없으면 요청을 공개할 수 없어서
 * (공개 때 템플릿 항목을 복사하는데 항목이 없으면 409) 데이터로 넣는다.
 *
 * <p>fieldKey는 문서 분석 비교 키(AnswerComparator의 gpa, gpa_scale, credits)와 같아야 한다.
 * JSON 칸에 문자열을 넣는 문법이 MySQL과 테스트용 H2가 달라서 SQL 대신 Java 마이그레이션으로 넣는다
 * (MySQL은 문자열을 그대로 JSON으로 읽고, H2는 FORMAT JSON을 붙여야 JSON으로 읽는다).
 */
public class V11__grade_certificate_template extends BaseJavaMigration {

    static final String CODE = "GRADE_CERTIFICATE";
    // 자동 번호(1)를 쓰면 테스트·로컬에서 손으로 넣는 작은 id와 겹치기 쉬워서 고정 id를 준다. 이미 쓰고 있으면 자동 번호
    static final long ID = 10;

    static final String FIELDS = """
        [
          {"fieldKey": "gpa", "label": "학기 평점", "fieldType": "NUMBER", "required": true,
           "validation": {"min": 0, "max": 4.5}, "helpText": "예: 3.85"},
          {"fieldKey": "gpa_scale", "label": "만점 기준", "fieldType": "SELECT_ONE", "required": true,
           "options": [{"value": "4.5", "label": "4.5"}, {"value": "4.3", "label": "4.3"}],
           "helpText": "성적증명서에 적힌 평점 만점을 고르세요."},
          {"fieldKey": "credits", "label": "취득 학점 (F 제외)", "fieldType": "NUMBER", "required": true,
           "validation": {"min": 0, "max": 30}, "helpText": "예: 18학점을 들었고 3학점이 F면 15"},
          {"fieldKey": "transcript", "label": "성적증명서", "fieldType": "FILE", "required": true,
           "validation": {"allowedExtensions": ["pdf", "jpg", "jpeg", "png"], "maxSizeMb": 10, "maxFiles": 1}},
          {"fieldKey": "note", "label": "전달할 내용", "fieldType": "TEXTAREA", "required": false,
           "validation": {"maxLength": 500}}
        ]
        """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        // 누가 손으로 먼저 넣어 둔 서버에서도 실패하지 않게 같은 코드가 있으면 건너뛴다
        try (PreparedStatement exists = connection.prepareStatement("SELECT COUNT(*) FROM request_templates WHERE code = ?")) {
            exists.setString(1, CODE);
            try (ResultSet rs = exists.executeQuery()) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
        }
        boolean idFree;
        try (PreparedStatement taken = connection.prepareStatement("SELECT COUNT(*) FROM request_templates WHERE id = ?")) {
            taken.setLong(1, ID);
            try (ResultSet rs = taken.executeQuery()) {
                rs.next();
                idFree = rs.getLong(1) == 0;
            }
        }
        boolean h2 = connection.getMetaData().getDatabaseProductName().toUpperCase().contains("H2");
        String json = h2 ? "? FORMAT JSON" : "?";
        String sql = idFree
            ? "INSERT INTO request_templates (id, code, name, default_fields) VALUES (?, ?, ?, " + json + ")"
            : "INSERT INTO request_templates (code, name, default_fields) VALUES (?, ?, " + json + ")";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            int i = 1;
            if (idFree) {
                insert.setLong(i++, ID);
            }
            insert.setString(i++, CODE);
            insert.setString(i++, "성적증명서");
            insert.setString(i, FIELDS);
            insert.executeUpdate();
        }
    }
}

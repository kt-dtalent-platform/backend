package kr.co.ktscholarship.backend.api;

import static kr.co.ktscholarship.backend.api.Database.*;

import java.io.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
// 엑셀 출력
public class ExportService {
  // DB 접근
  private final Database d;
  // 제출 업무
  private final WorkflowService w;

  // 의존성 주입
  public ExportService(Database d, WorkflowService w) {
    this.d = d;
    this.w = w;
  }

  @Transactional
  // 제출 현황 출력
  public byte[] export(AuthService.Actor a, long id, boolean notSubmittedOnly) throws IOException {
    w.manager(a);
    w.request(a, id, false);
    var rows =
        d.rows(
            "SELECT t.scholar_id,u.name,u.email,s.status"
                + (notSubmittedOnly ? "" : ",s.answers")
                + WorkflowService.targetSource(notSubmittedOnly)
                + " ORDER BY t.scholar_id LIMIT 10001",
            id);
    ApiException.require(rows.size() <= 10000, 409, "EXPORT_LIMIT", "내보내기 최대 10,000건을 초과했습니다.");
    try (var book = new XSSFWorkbook();
        var out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("제출 현황");
      String[] headers = {"장학생 ID", "이름", "이메일", "상태", "평점", "만점", "학점"};
      var header = sheet.createRow(0);
      for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
      int n = 1;
      for (var item : rows) {
        var row = sheet.createRow(n++);
        String[] values = {
          text(item, "scholar_id"),
          text(item, "name"),
          text(item, "email"),
          item.get("status") == null ? "NOT_STARTED" : text(item, "status"),
          "",
          "",
          ""
        };
        if (item.get("answers") != null) {
          var answers = d.object(item.get("answers"));
          values[4] = text(answers, "gpa");
          values[5] = text(answers, "gpa_scale");
          values[6] = text(answers, "credits");
        }
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
      }
      sheet.createFreezePane(0, 1);
      for (int i = 0; i < headers.length; i++) sheet.setColumnWidth(i, 22 * 256);
      book.write(out);
      d.audit(a.id(), "EXPORT_SUBMISSIONS", "REQUEST", id,rows.size());
      return out.toByteArray();
    }
  }
}

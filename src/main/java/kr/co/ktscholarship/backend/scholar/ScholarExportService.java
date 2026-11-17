package kr.co.ktscholarship.backend.scholar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.common.util.PhoneMasker;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScholarExportService {
    private static final Map<String,String> LABELS = Map.ofEntries(
        Map.entry("name","이름"), Map.entry("cohort","기수"), Map.entry("university","학교"),
        Map.entry("major","전공"), Map.entry("studentNo","학번"), Map.entry("email","이메일"),
        Map.entry("phone","연락처"), Map.entry("status","상태"), Map.entry("completedYear","수료 연도"),
        Map.entry("withdrawalReason","중단 사유"), Map.entry("withdrawalReasonDetail","중단 상세 사유"),
        Map.entry("accountStatus","계정 상태"));
    private static final Set<String> STATES = Set.of("ENROLLED","LEAVE","COMPLETED","GRADUATED","WITHDRAWN");
    private final ScholarExportRepository repository;
    public ScholarExportService(ScholarExportRepository repository) { this.repository = repository; }

    @Transactional
    public byte[] export(long actor, String format, String status, Long cohort, String columns, String ip) {
        if (!"xlsx".equals(format)) throw invalid();
        var states = split(status);
        if (!STATES.containsAll(states)) throw invalid();
        var selected = new LinkedHashSet<String>();
        selected.add("name");
        selected.addAll(columns == null
            ? List.of("cohort","university","major","email","status","completedYear","withdrawalReason","accountStatus")
            : split(columns));
        if (!LABELS.keySet().containsAll(selected)) throw invalid();
        var rows = repository.find(cohort, states);
        byte[] bytes = workbook(selected, rows);
        repository.audit(actor, rows.size(), ip, Instant.now());
        return bytes;
    }

    private Set<String> split(String text) {
        var values = new LinkedHashSet<String>();
        if (text == null) return values;
        for (String value : text.split(",", -1)) {
            if (value.isBlank()) throw invalid();
            values.add(value.trim());
        }
        return values;
    }

    private byte[] workbook(Set<String> selected, List<ScholarExportRepository.Row> rows) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("장학생 명단");
            var header = sheet.createRow(0);
            int column = 0;
            for (String key : selected) header.createCell(column++).setCellValue(LABELS.get(key));
            int index = 1;
            for (var source : rows) {
                var row = sheet.createRow(index++);
                column = 0;
                for (String key : selected) {
                    Object value = value(source, key);
                    var cell = row.createCell(column++);
                    if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                    else cell.setCellValue(value == null ? "" : value.toString());
                }
            }
            sheet.createFreezePane(0, 1);
            for (int c = 0; c < selected.size(); c++) sheet.setColumnWidth(c, 24 * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("장학생 명단 생성 실패", exception);
        }
    }

    private Object value(ScholarExportRepository.Row row, String key) {
        return switch (key) {
            case "name" -> row.name(); case "cohort" -> row.cohort(); case "university" -> row.university();
            case "major" -> row.major(); case "studentNo" -> row.studentNo(); case "email" -> row.email();
            case "phone" -> PhoneMasker.mask(row.phone()); case "completedYear" -> row.completedYear();
            case "accountStatus" -> row.accountStatus(); case "withdrawalReasonDetail" -> row.withdrawalReasonDetail();
            case "status" -> switch (row.status()) {
                case "ENROLLED" -> "재학"; case "LEAVE" -> "휴학"; case "COMPLETED" -> "수료";
                case "GRADUATED" -> "졸업(기존)"; case "WITHDRAWN" -> "중단"; default -> row.status();
            };
            case "withdrawalReason" -> row.withdrawalReason() == null ? "" : switch (row.withdrawalReason()) {
                case "ACADEMIC_STATUS" -> "학적 변동"; case "DOUBLE_BENEFIT" -> "중복 수혜";
                case "GRADE_NOT_MET" -> "성적 미달"; case "ACTIVITY_NOT_MET" -> "활동 미달";
                case "VOLUNTARY" -> "자진 중단"; case "OTHER" -> "기타"; default -> row.withdrawalReason();
            };
            default -> throw invalid();
        };
    }

    private BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

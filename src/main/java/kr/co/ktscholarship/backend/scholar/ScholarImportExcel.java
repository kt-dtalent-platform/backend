package kr.co.ktscholarship.backend.scholar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ScholarImportExcel {
    static final int MAX_ROWS = 200;
    static final long MAX_BYTES = 1024 * 1024;
    static final List<String> HEADERS = List.of("이름", "이메일", "학교", "연락처", "전공", "학번", "학교ID");
    private static final List<String> FIELDS = List.of("name", "email", "university", "phone", "major", "studentNo", "schoolId");

    public List<ImportRow> read(MultipartFile file, long cohortId) {
        String filename = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > MAX_BYTES || filename == null
            || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw invalid(0, "file", "1MB 이하의 비어 있지 않은 .xlsx 파일을 올려 주세요.");
        }
        try (var input = file.getInputStream(); var workbook = WorkbookFactory.create(input)) {
            if (!(workbook instanceof XSSFWorkbook) || workbook.getNumberOfSheets() != 1) {
                throw invalid(0, "file", "시트가 하나인 .xlsx 파일을 올려 주세요.");
            }
            var sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() != HEADERS.size()) {
                throw invalid(1, "header", "다운로드한 양식의 열을 그대로 사용해 주세요.");
            }
            for (int column = 0; column < HEADERS.size(); column++) {
                Cell cell = header.getCell(column);
                if (cell == null || cell.getCellType() != CellType.STRING
                    || !HEADERS.get(column).equals(cell.getStringCellValue().strip())) {
                    throw invalid(1, "header", "양식의 열 순서: " + String.join(" / ", HEADERS));
                }
            }
            if (sheet.getLastRowNum() > MAX_ROWS) {
                throw invalid(0, "file", "한 번에 최대 200명까지 입력할 수 있습니다(2~201행).");
            }
            List<ImportRow> rows = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || isBlank(row)) {
                    continue;
                }
                int number = index + 1;
                if (row.getLastCellNum() > HEADERS.size()) {
                    errors.add(new RowError(number, "row", "양식에 없는 열에 값이 있습니다."));
                    continue;
                }
                String[] values = new String[6];
                for (int column = 0; column < values.length; column++) {
                    Cell cell = row.getCell(column);
                    if (!isBlank(cell)) {
                        if (cell.getCellType() != CellType.STRING) {
                            errors.add(new RowError(number, FIELDS.get(column), "수식·숫자 대신 문자 형식의 값을 입력해 주세요."));
                        } else {
                            values[column] = cell.getStringCellValue().strip();
                        }
                    }
                }
                Long schoolId = schoolId(row.getCell(6), number, errors);
                rows.add(new ImportRow(number, new ScholarCreateRequest(cohortId, values[0], values[1], values[3],
                    values[2], values[4], values[5], schoolId)));
            }
            if (!errors.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, errors);
            }
            if (rows.isEmpty()) {
                throw invalid(0, "file", "등록할 장학생을 2행부터 입력해 주세요.");
            }
            return rows;
        } catch (IOException | POIXMLException | EncryptedDocumentException | IllegalArgumentException exception) {
            throw invalid(0, "file", "읽을 수 없는 엑셀 파일입니다. 암호가 없는 .xlsx 양식을 사용해 주세요.");
        }
    }

    public byte[] template() throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("장학생 등록");
            var textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            var row = sheet.createRow(0);
            for (int column = 0; column < HEADERS.size(); column++) {
                var cell = row.createCell(column);
                cell.setCellValue(HEADERS.get(column));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, (column == 1 ? 32 : 22) * 256);
                if (column < 6) {
                    sheet.setDefaultColumnStyle(column, textStyle);
                }
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private Long schoolId(Cell cell, int row, List<RowError> errors) {
        if (isBlank(cell)) {
            return null;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue()).longValueExact();
            }
            if (cell.getCellType() == CellType.STRING) {
                return Long.valueOf(cell.getStringCellValue().strip());
            }
        } catch (NumberFormatException | ArithmeticException exception) {
            // 행 오류로 모아서 반환한다.
        }
        errors.add(new RowError(row, "schoolId", "학교ID는 정수 또는 빈칸이어야 합니다."));
        return null;
    }

    private boolean isBlank(Row row) {
        for (Cell cell : row) {
            if (!isBlank(cell)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlank(Cell cell) {
        return cell == null || cell.getCellType() == CellType.BLANK
            || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank());
    }

    private BusinessException invalid(int row, String field, String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, List.of(new RowError(row, field, message)));
    }

    public record ImportRow(int row, ScholarCreateRequest request) { }
    public record RowError(int row, String field, String message) { }
}

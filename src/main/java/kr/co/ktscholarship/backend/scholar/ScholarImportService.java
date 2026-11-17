package kr.co.ktscholarship.backend.scholar;

import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.scholar.ScholarImportExcel.RowError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ScholarImportService {
    private final ScholarImportExcel excel;
    private final Validator validator;
    private final ScholarRegistrationService registration;

    public ScholarImportService(ScholarImportExcel excel, Validator validator, ScholarRegistrationService registration) {
        this.excel = excel;
        this.validator = validator;
        this.registration = registration;
    }

    @Transactional
    public ScholarImportResponse register(long cohortId, MultipartFile file) {
        var rows = excel.read(file, cohortId);
        List<RowError> errors = new ArrayList<>();
        for (var row : rows) {
            validator.validate(row.request()).stream()
                .sorted(java.util.Comparator.comparing(v -> v.getPropertyPath().toString()))
                .forEach(v -> errors.add(new RowError(row.row(), v.getPropertyPath().toString(), v.getMessage())));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, errors);
        }
        var emails = new HashSet<String>();
        for (var row : rows) {
            if (!emails.add(row.request().email().toLowerCase(Locale.ROOT))) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL,
                    List.of(new RowError(row.row(), "email", "파일 안에 이메일이 중복됩니다.")));
            }
        }
        List<ScholarImportResponse.Item> items = new ArrayList<>();
        for (var row : rows) {
            try {
                var created = registration.register(row.request());
                items.add(new ScholarImportResponse.Item(row.row(), created.id(), row.request().email(), created.temporaryPassword()));
            } catch (BusinessException exception) {
                String field = switch (exception.getErrorCode()) {
                    case DUPLICATE_EMAIL -> "email";
                    case COHORT_NOT_FOUND -> "cohortId";
                    case INVALID_REQUEST -> "schoolId";
                    default -> throw exception;
                };
                throw new BusinessException(exception.getErrorCode(),
                    List.of(new RowError(row.row(), field, exception.getMessage())));
            }
        }
        return new ScholarImportResponse(items.size(), items);
    }
}

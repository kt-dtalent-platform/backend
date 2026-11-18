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
        return registerRows(excel.read(file, cohortId), false);
    }

    @Transactional
    public ScholarImportResponse register(ScholarBulkCreateRequest request) {
        List<ScholarImportExcel.ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < request.scholars().size(); i++) {
            rows.add(new ScholarImportExcel.ImportRow(i + 2, request.scholars().get(i).inCohort(request.cohortId())));
        }
        return registerRows(rows, true);
    }

    private ScholarImportResponse registerRows(List<ScholarImportExcel.ImportRow> rows, boolean bulk) {
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
                    bulk ? List.of(row.request().email())
                        : List.of(new RowError(row.row(), "email", "명단 안에 이메일이 중복됩니다.")));
            }
        }
        List<ScholarImportResponse.Item> items = new ArrayList<>();
        for (var row : rows) {
            try {
                var created = registration.register(row.request());
                items.add(new ScholarImportResponse.Item(row.row(), created.id(), row.request().email(), created.temporaryPassword()));
            } catch (BusinessException exception) {
                if (bulk && exception.getErrorCode() == ErrorCode.DUPLICATE_EMAIL) {
                    throw new BusinessException(ErrorCode.DUPLICATE_EMAIL, List.of(row.request().email()));
                }
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

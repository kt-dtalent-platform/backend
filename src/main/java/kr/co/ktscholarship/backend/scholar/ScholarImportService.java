package kr.co.ktscholarship.backend.scholar;

import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScholarImportService {
    private final Validator validator;
    private final ScholarRegistrationRepository repository;
    private final ScholarRegistrationService registration;

    public ScholarImportService(Validator validator, ScholarRegistrationService registration, ScholarRegistrationRepository repository) {
        this.validator = validator;
        this.repository = repository;
        this.registration = registration;
    }

    @Transactional
    public ScholarImportResponse register(ScholarBulkCreateRequest request) {
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < request.scholars().size(); i++) {
            rows.add(new ImportRow(i + 2, request.scholars().get(i).inCohort(request.cohortId())));
        }
        return registerRows(rows);
    }

    private ScholarImportResponse registerRows(List<ImportRow> rows) {
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
        var duplicates = new LinkedHashSet<String>();
        for (var row : rows) {
            if (!emails.add(row.request().email().toLowerCase(Locale.ROOT))) {
                duplicates.add(row.request().email());
            }
        }
        var existing = new HashSet<String>();
        repository.findActiveEmails(List.copyOf(emails))
            .forEach(email -> existing.add(email.toLowerCase(Locale.ROOT)));
        for (var row : rows) {
            if (existing.contains(row.request().email().toLowerCase(Locale.ROOT))) {
                duplicates.add(row.request().email());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL, List.copyOf(duplicates));
        }
        List<ScholarImportResponse.Item> items = new ArrayList<>();
        for (var row : rows) {
            try {
                var created = registration.register(row.request());
                items.add(new ScholarImportResponse.Item(row.row(), created.id(), row.request().email(), created.temporaryPassword()));
            } catch (BusinessException exception) {
                if (exception.getErrorCode() == ErrorCode.DUPLICATE_EMAIL) {
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
    private record ImportRow(int row, ScholarCreateRequest request) { }
    public record RowError(int row, String field, String message) { }
}

package kr.co.ktscholarship.backend.scholar;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScholarBankService {
    private final ScholarBankRepository repository;
    public ScholarBankService(ScholarBankRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public MaskedAccount find(long user) {
        var row = repository.find(user, false).orElseThrow(() -> new BusinessException(ErrorCode.SCHOLAR_NOT_FOUND));
        if (row.number() == null) return null;
        String tail = row.number().substring(Math.max(0, row.number().length() - 4));
        return new MaskedAccount(row.bank(), "****" + tail, row.holder());
    }

    @Transactional
    public Updated update(long user, ScholarBankAccount request) {
        String number = request.accountNumber().replace("-", "").replace(" ", "");
        if (number.length() < 5) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        repository.find(user, true).orElseThrow(() -> new BusinessException(ErrorCode.SCHOLAR_NOT_FOUND));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        repository.update(user, new ScholarBankAccount(request.bankName().trim(), number, request.accountHolder().trim()), now);
        return new Updated(now);
    }

    public record MaskedAccount(String bankName, String maskedAccountNumber, String accountHolder) {}
    public record Updated(Instant updatedAt) {}
}

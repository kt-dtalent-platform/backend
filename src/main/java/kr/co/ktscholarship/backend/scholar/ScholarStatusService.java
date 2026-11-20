package kr.co.ktscholarship.backend.scholar;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScholarStatusService {
    private final ScholarStatusRepository repository;
    public ScholarStatusService(ScholarStatusRepository repository) { this.repository = repository; }

    @Transactional
    public Changed change(long id, ScholarStatusRequest request) {
        var change = validate(request);
        var rows = repository.lock(List.of(id));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.SCHOLAR_NOT_FOUND);
        var row = rows.getFirst();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (same(row, change)) return new Changed(id, row.updatedAt());
        repository.update(id, change, accountStatus(row, change), now);
        return new Changed(id, now);
    }

    @Transactional
    public BulkChanged changeAll(ScholarBulkStatusRequest request) {
        var change = validate(request.change());
        var ids = request.scholarIds().stream().distinct().sorted().toList();
        var rows = repository.lock(ids);
        // 한 명이라도 없으면 아무도 변경하지 않는다.
        if (rows.size() != ids.size()) throw new BusinessException(ErrorCode.SCHOLAR_NOT_FOUND);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int updated = 0;
        for (var row : rows) {
            if (!same(row, change)) { repository.update(row.id(), change, accountStatus(row, change), now); updated++; }
        }
        return new BulkChanged(updated);
    }

    private ScholarStatusRequest validate(ScholarStatusRequest request) {
        String detail = request.withdrawalReasonDetail() == null ? null : request.withdrawalReasonDetail().trim();
        boolean completed = request.status() == ScholarStatusRequest.Status.COMPLETED;
        boolean withdrawn = request.status() == ScholarStatusRequest.Status.WITHDRAWN;
        boolean other = request.withdrawalReason() == ScholarStatusRequest.Reason.OTHER;
        if (completed != (request.completedYear() != null)
            || withdrawn != (request.withdrawalReason() != null)
            || (withdrawn && other ? detail == null || detail.isBlank() : detail != null && !detail.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "수료 연도·중단 사유를 상태에 맞게 입력해 주세요.");
        }
        return new ScholarStatusRequest(request.status(), request.completedYear(), request.withdrawalReason(),
            withdrawn && other ? detail : null);
    }

    private boolean same(ScholarStatusRepository.Row row, ScholarStatusRequest change) {
        return row.status().equals(change.status().name()) && row.accountStatus().equals(accountStatus(row, change).name())
            && Objects.equals(row.year(), change.completedYear())
            && Objects.equals(row.reason(), change.withdrawalReason() == null ? null : change.withdrawalReason().name())
            && Objects.equals(row.detail(), change.withdrawalReasonDetail());
    }

    private UserStatus accountStatus(ScholarStatusRepository.Row row, ScholarStatusRequest change) {
        if (change.status() == ScholarStatusRequest.Status.WITHDRAWN) return UserStatus.INACTIVE;
        // 장학생 상태 변경으로 인증 영역의 잠금을 해제하지 않는다.
        return row.accountStatus().equals(UserStatus.LOCKED.name()) ? UserStatus.LOCKED : UserStatus.ACTIVE;
    }

    public record Changed(long id, Instant updatedAt) {}
    public record BulkChanged(int updated) {}
}

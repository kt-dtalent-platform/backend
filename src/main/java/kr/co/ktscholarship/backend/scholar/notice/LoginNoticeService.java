package kr.co.ktscholarship.backend.scholar.notice;

import static kr.co.ktscholarship.backend.scholar.notice.LoginNoticeModels.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LoginNoticeService {
    private final LoginNoticeRepository repository;
    private final LoginNoticeMailSender mail;

    LoginNoticeService(LoginNoticeRepository repository, LoginNoticeMailSender mail) {
        this.repository = repository;
        this.mail = mail;
    }

    Sender sender() {
        return repository.sender().orElse(null);
    }

    @Transactional
    void saveSender(Sender sender) {
        repository.saveSender(sender);
    }

    List<Item> results(List<Long> ids) {
        if (ids.isEmpty() || ids.size() > 500 || ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return repository.notices(ids);
    }

    @Transactional
    Result enqueue(SendRequest request, long actor) {
        if (!mail.available() || repository.sender().isEmpty()) {
            throw new BusinessException(ErrorCode.MAIL_UNAVAILABLE);
        }
        if (new HashSet<>(request.scholarIds()).size() != request.scholarIds().size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, List.of("scholarIds"));
        }
        List<Item> items = new ArrayList<>();
        int queued = 0;
        boolean reissue = Boolean.TRUE.equals(request.reissue());
        // 발송 처리와 같은 순서로 사용자 행을 잠가 중복 접수와 교착을 방지한다.
        for (long id : request.scholarIds().stream().sorted().toList()) {
            var user = repository.lockAccount(id).orElse(null);
            String error;
            if (user == null) {
                error = "SCHOLAR_NOT_FOUND";
            } else if (!user.status().equals("ACTIVE")) {
                error = "ACCOUNT_DISABLED";
            } else if (!user.passwordChangeRequired() && !reissue) {
                error = "PASSWORD_ALREADY_CHANGED";
            } else {
                error = null;
            }
            var notice = repository.notice(id).orElse(null);
            if (error == null && notice != null) {
                if (List.of("PENDING", "SENDING").contains(notice.status())) {
                    error = "ALREADY_QUEUED";
                } else if (notice.status().equals("SENT") && !reissue) {
                    error = "ALREADY_SENT";
                }
            }
            if (error != null) {
                items.add(new Item(id, "SKIPPED", error));
                continue;
            }
            repository.enqueue(id, actor, reissue);
            items.add(new Item(id, "PENDING", null));
            queued++;
        }
        return new Result(queued, items);
    }
}

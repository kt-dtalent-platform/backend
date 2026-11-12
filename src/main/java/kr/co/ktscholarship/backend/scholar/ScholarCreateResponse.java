package kr.co.ktscholarship.backend.scholar;

// 임시 비밀번호는 등록 응답에서 한 번만 제공하며 DB에는 해시만 저장한다.
public record ScholarCreateResponse(long id, String accountStatus, String temporaryPassword) {
    @Override
    public String toString() {
        return "ScholarCreateResponse[id=" + id + ", accountStatus=" + accountStatus + ", temporaryPassword=REDACTED]";
    }
}

package kr.co.ktscholarship.backend.scholar.notice;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public final class LoginNoticeModels {
    private LoginNoticeModels() {}
    @Schema(name="LoginNoticeSender")
    public record Sender(@NotBlank @Email @Size(max=255) @Pattern(regexp="[^\\r\\n]+") String fromAddress,
                         @NotBlank @Size(max=100) @Pattern(regexp="[^\\r\\n]+") String senderName) {}
    @Schema(name="LoginNoticeSendRequest")
    public record SendRequest(@NotEmpty @Size(max=500) List<@NotNull @Positive Long> scholarIds, Boolean reissue) {}
    @Schema(name="LoginNoticeStatus")
    public record Item(long scholarId, String status, String errorCode) {}
    @Schema(name="LoginNoticeResult")
    public record Result(int queued, List<Item> items) {}
    record Account(long id, String name, String email, boolean passwordChangeRequired, String status) {}
    record Delivery(long scholarId, String name, String email, String temporaryPassword, Sender sender) {
        @Override public String toString() { return "LoginNoticeDelivery[scholarId="+scholarId+"]"; }
    }
}

package kr.co.ktscholarship.backend.scholar;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ScholarImportResponse(int created, List<Item> items) {
    @Schema(name = "ScholarImportItem")
    public record Item(int row, long id, String email, String temporaryPassword) {
        @Override public String toString() { return "ScholarImportItem[row=" + row + ", id=" + id + "]"; }
    }
}

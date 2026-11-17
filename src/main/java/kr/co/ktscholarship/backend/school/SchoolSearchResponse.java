package kr.co.ktscholarship.backend.school;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record SchoolSearchResponse(List<Item> items) {
    @Schema(name = "SchoolSearchItem")
    public record Item(long id, String name, String region) {}
}

package kr.co.ktscholarship.backend.requesttemplate;

import java.util.List;
import java.util.Map;

public record RequestTemplateListResponse(List<Item> items) {

    public record Item(long id, String code, String name, List<Map<String, Object>> fields) {
    }
}

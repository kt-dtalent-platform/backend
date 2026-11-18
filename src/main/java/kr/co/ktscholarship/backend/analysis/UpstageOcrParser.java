package kr.co.ktscholarship.backend.analysis;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 업스테이지 OCR 응답(JSON)에서 첫 페이지의 단어·좌표·확신도만 꺼낸다 */
public final class UpstageOcrParser {

    private UpstageOcrParser() {
    }

    public static OcrPage parse(JsonMapper mapper, String json) {
        JsonNode pages = mapper.readTree(json).path("pages");
        if (!pages.isArray() || pages.isEmpty()) {
            return new OcrPage(0, 0, List.of());
        }
        JsonNode page = pages.get(0);
        List<OcrPage.Word> words = new ArrayList<>();
        for (JsonNode word : page.path("words")) {
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for (JsonNode v : word.path("boundingBox").path("vertices")) {
                int x = v.path("x").asInt(), y = v.path("y").asInt();
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
            if (x0 == Integer.MAX_VALUE) {
                continue;
            }
            words.add(new OcrPage.Word(word.path("text").asString(""), x0, y0, x1, y1,
                word.path("confidence").asDouble(0)));
        }
        return new OcrPage(page.path("width").asInt(), page.path("height").asInt(), words);
    }
}

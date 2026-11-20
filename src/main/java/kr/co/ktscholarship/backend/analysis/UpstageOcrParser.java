package kr.co.ktscholarship.backend.analysis;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 업스테이지 OCR 응답(JSON)에서 단어·좌표·확신도를 꺼낸다.
 *
 * <p>여러 페이지면 페이지를 위에서 아래로 이어 붙여 한 장처럼 만든다(다음 페이지의 y에 앞 페이지 높이를 더한다).
 * 대상 학기가 2페이지에 있어도 찾을 수 있게
 */
public final class UpstageOcrParser {

    private UpstageOcrParser() {
    }

    public static OcrPage parse(JsonMapper mapper, String json) {
        JsonNode pages = mapper.readTree(json).path("pages");
        if (!pages.isArray() || pages.isEmpty()) {
            return new OcrPage(0, 0, List.of());
        }
        List<OcrPage.Word> words = new ArrayList<>();
        int width = 0;
        int offsetY = 0;
        for (JsonNode page : pages) {
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
                words.add(new OcrPage.Word(word.path("text").asString(""), x0, y0 + offsetY, x1, y1 + offsetY,
                    word.path("confidence").asDouble(0)));
            }
            width = Math.max(width, page.path("width").asInt());
            offsetY += page.path("height").asInt();
        }
        return new OcrPage(width, offsetY, words);
    }
}

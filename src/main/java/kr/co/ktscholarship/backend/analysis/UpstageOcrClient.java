package kr.co.ktscholarship.backend.analysis;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 업스테이지 Document OCR(model=ocr). 단어마다 좌표·확신도를 돌려줘서 2단 성적표의 칸을 나눌 수 있다.
 * 보낸 문서는 업스테이지에 저장·학습되지 않는 조건으로 골랐다(11/10). 키가 없으면 꺼진 상태로 동작한다.
 */
@Component
public class UpstageOcrClient implements OcrClient {

    static final String DEFAULT_URL = "https://api.upstage.ai/v1/document-digitization";

    private final RestClient restClient;
    private final JsonMapper mapper;
    private final String apiKey;

    @Autowired
    public UpstageOcrClient(
        JsonMapper mapper,
        @Value("${app.ocr.upstage.api-key:${UPSTAGE_API_KEY:}}") String apiKey,
        @Value("${app.ocr.upstage.url:" + DEFAULT_URL + "}") String url,
        @Value("${app.ocr.upstage.timeout:30s}") Duration timeout
    ) {
        this(RestClient.builder().requestFactory(requestFactory(timeout)), mapper, apiKey, url);
    }

    UpstageOcrClient(RestClient.Builder builder, JsonMapper mapper, String apiKey, String url) {
        this.restClient = builder.baseUrl(url).build();
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    @Override
    public OcrPage read(byte[] document, String fileName) {
        if (!enabled()) {
            throw new OcrUnavailableException("OCR_NOT_CONFIGURED");
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("document", new ByteArrayResource(document) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        body.add("model", "ocr");
        String json;
        try {
            json = restClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
        } catch (RestClientException exception) {
            // 예외 메시지에 요청 헤더(키)가 섞이지 않도록 원인 종류만 남긴다
            throw new OcrUnavailableException("OCR_REQUEST_FAILED: " + exception.getClass().getSimpleName(), exception);
        }
        if (json == null || json.isBlank()) {
            throw new OcrUnavailableException("OCR_EMPTY_RESPONSE");
        }
        try {
            return UpstageOcrParser.parse(mapper, json);
        } catch (JacksonException exception) {
            // 잘린 응답 등 JSON이 깨졌으면 분석 실패로 남기고 담당자가 원본으로 확인한다
            throw new OcrUnavailableException("OCR_INVALID_RESPONSE", exception);
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(timeout);
        return factory;
    }
}

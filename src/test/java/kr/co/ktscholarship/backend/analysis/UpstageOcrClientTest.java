package kr.co.ktscholarship.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class UpstageOcrClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String URL = "https://ocr.example.test/v1/document-digitization";

    @Test
    void sendsDocumentWithBearerKeyAndParsesWords() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-key"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"model\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"transcript.pdf\"")))
            .andRespond(withSuccess("""
                {"pages":[{"width":1240,"height":1754,"words":[
                  {"text":"평점평균:","confidence":0.97,"boundingBox":{"vertices":[{"x":787,"y":460},{"x":866,"y":460},{"x":866,"y":478},{"x":787,"y":478}]}},
                  {"text":"3.75","confidence":0.99,"boundingBox":{"vertices":[{"x":871,"y":461},{"x":910,"y":461},{"x":910,"y":477},{"x":871,"y":477}]}}
                ]}]}
                """, MediaType.APPLICATION_JSON));

        OcrPage page = new UpstageOcrClient(builder, MAPPER, "test-key", URL).read(new byte[] {1, 2, 3}, "transcript.pdf");

        server.verify();
        assertThat(page.width()).isEqualTo(1240);
        assertThat(page.words()).extracting(OcrPage.Word::text).containsExactly("평점평균:", "3.75");
        assertThat(page.words().get(1)).extracting(OcrPage.Word::x0, OcrPage.Word::y1, OcrPage.Word::confidence)
            .containsExactly(871, 477, 0.99);
    }

    @Test
    void isDisabledWithoutKey() {
        UpstageOcrClient client = new UpstageOcrClient(RestClient.builder(), MAPPER, "  ", URL);

        assertThat(client.enabled()).isFalse();
        assertThatThrownBy(() -> client.read(new byte[] {1}, "a.pdf"))
            .isInstanceOf(OcrUnavailableException.class)
            .hasMessage("OCR_NOT_CONFIGURED");
    }

    @Test
    void treatsBrokenJsonAsOcrFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"pages\":[", MediaType.APPLICATION_JSON));

        UpstageOcrClient client = new UpstageOcrClient(builder, MAPPER, "test-key", URL);

        assertThatThrownBy(() -> client.read(new byte[] {1}, "a.pdf"))
            .isInstanceOf(OcrUnavailableException.class)
            .hasMessage("OCR_INVALID_RESPONSE");
    }

    @Test
    void wrapsServiceErrorWithoutLeakingKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withServerError());

        UpstageOcrClient client = new UpstageOcrClient(builder, MAPPER, "secret-key", URL);

        assertThatThrownBy(() -> client.read(new byte[] {1}, "a.pdf"))
            .isInstanceOf(OcrUnavailableException.class)
            .hasMessageStartingWith("OCR_REQUEST_FAILED")
            .hasMessageNotContaining("secret-key");
    }
}

package kr.co.ktscholarship.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// 상태 API 검증
class BackendApplicationTests {

  @Value("${local.server.port}")
  private int port;

  @Test
  // 상태 및 상세 미노출 검증
  void healthEndpointReportsUp() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
              .timeout(Duration.ofSeconds(10))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      var body = JsonMapper.builder().build().readTree(response.body());
      assertThat(body.path("status").asString()).isEqualTo("UP");
      assertThat(body.has("components")).isFalse();
    }
  }
}

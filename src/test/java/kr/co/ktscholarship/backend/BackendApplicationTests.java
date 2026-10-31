package kr.co.ktscholarship.backend;

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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BackendApplicationTests {

	@Value("${local.server.port}")
	private int port;

	@Test
	void healthEndpointReportsUp() throws Exception {
		// 서버 및 DB 연결 상태 검증
		try (var client = HttpClient.newHttpClient()) {
			var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
					.timeout(Duration.ofSeconds(10)).build();
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			var body = JsonMapper.builder().build().readTree(response.body());
			assertThat(body.path("status").asString()).isEqualTo("UP");
			assertThat(body.has("components")).isFalse();
		}
	}

}

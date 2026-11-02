package kr.co.ktscholarship.backend.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import kr.co.ktscholarship.backend.common.response.ApiResponse;

class GlobalExceptionHandlerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void successResponseUsesTheCommonEnvelope() throws Exception {
        mockMvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.success.data.id").value(1))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.meta.path").value("/test/success"));
    }

    @Test
    void businessExceptionUsesTheCommonFailureEnvelope() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.success").isEmpty())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.meta.path").value("/test/not-found"));
    }

    @Test
    void accessDeniedBusinessExceptionReturnsForbidden() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void validationFailureUsesTheCommonInvalidRequestResponse() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0]").value("name"));
    }

    @Test
    void unsupportedHttpMethodKeepsTheMethodNotAllowedStatus() throws Exception {
        mockMvc.perform(post("/test/success"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void missingResourceKeepsTheNotFoundStatus() throws Exception {
        mockMvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @RestController
    @RequestMapping(value = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
    static class TestController {
        @GetMapping("/success")
        ApiResponse<Map<String, Integer>> success() {
            return ApiResponse.success(Map.of("id", 1), "/test/success");
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @GetMapping("/forbidden")
        void forbidden() {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        @PostMapping("/validated")
        void validated(@Valid @RequestBody ValidatedRequest request) {
        }

        record ValidatedRequest(@NotBlank String name) {
        }
    }
}

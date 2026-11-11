package kr.co.ktscholarship.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class SubmissionDraftRequestTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsEmptyAnswersAndInitialVersion() {
        var request = MAPPER.readValue("{\"answers\":{},\"version\":0}", SubmissionDraftRequest.class);

        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
        assertThat(request.answers()).isEmpty();
        assertThat(request.version()).isZero();
    }

    @Test
    void preservesPartialInputAndVersion() {
        var request = MAPPER.readValue("""
            {"answers":{"test_field":"작성 중"},"version":4}
            """, SubmissionDraftRequest.class);

        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
        assertThat(request.answers()).containsOnlyKeys("test_field").containsEntry("test_field", "작성 중");
        assertThat(request.version()).isEqualTo(4);
    }

    @Test
    void requiresAnswersAndVersion() {
        var request = MAPPER.readValue("{}", SubmissionDraftRequest.class);

        assertThat(FACTORY.getValidator().validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsExactlyInAnyOrder("answers", "version");
    }

    @Test
    void rejectsNegativeVersion() {
        var request = MAPPER.readValue("{\"answers\":{},\"version\":-1}", SubmissionDraftRequest.class);

        assertThat(FACTORY.getValidator().validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsExactly("version");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1.0", "\"0\"", "true", "2147483648"})
    void rejectsNonIntegerOrOverflowingVersion(String version) {
        String body = "{\"answers\":{},\"version\":" + version + "}";

        assertThatThrownBy(() -> MAPPER.readValue(body, SubmissionDraftRequest.class))
            .isInstanceOf(JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"text\"", "3", "true"})
    void requiresAnswersToBeAnObject(String answers) {
        String body = "{\"answers\":" + answers + ",\"version\":0}";

        assertThatThrownBy(() -> MAPPER.readValue(body, SubmissionDraftRequest.class))
            .isInstanceOf(JacksonException.class);
    }
}

package kr.co.ktscholarship.backend.file;

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

class FilePresignRequestTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsUploadMetadata() {
        var request = MAPPER.readValue("""
            {"fieldKey":"receipt","fileName":"영수증.jpg","contentType":"image/jpeg","size":1843200}
            """, FilePresignRequest.class);

        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
        assertThat(request.size()).isEqualTo(1843200L);
        assertThat(request.fileName()).isEqualTo("영수증.jpg");
    }

    @Test
    void requiresAllMetadata() {
        var request = MAPPER.readValue("{}", FilePresignRequest.class);

        assertThat(FACTORY.getValidator().validate(request))
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactlyInAnyOrder("fieldKey", "fileName", "contentType", "size");
    }

    @Test
    void rejectsBlankAndOversizedMetadata() {
        var request = new FilePresignRequest(" ", "a".repeat(256), "a".repeat(101), 100L);

        assertThat(FACTORY.getValidator().validate(request))
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactlyInAnyOrder("fieldKey", "fileName", "contentType");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveSize(long size) {
        var request = new FilePresignRequest("receipt", "file.pdf", "application/pdf", size);

        assertThat(FACTORY.getValidator().validate(request))
            .extracting(v -> v.getPropertyPath().toString()).containsExactly("size");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1.0", "\"123\"", "true", "9223372036854775808"})
    void rejectsCoercedOrOverflowingSize(String size) {
        assertThatThrownBy(() -> MAPPER.readValue("{\"size\":" + size + "}", FilePresignRequest.class))
            .isInstanceOf(JacksonException.class);
    }

    @Test
    void preservesLongSizeWithoutIntegerOverflow() {
        var request = MAPPER.readValue("""
            {"fieldKey":"receipt","fileName":"file.pdf","contentType":"application/pdf","size":2147483648}
            """, FilePresignRequest.class);

        assertThat(request.size()).isEqualTo(2147483648L);
        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
    }
}

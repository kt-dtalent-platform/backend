package kr.co.ktscholarship.backend.scholar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class ScholarCreateRequestTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String REQUIRED_JSON = """
        {"cohortId":6,"name":"홍길동","email":"hong@example.test","birthDate":"2002-03-15"}
        """;

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsAllSpecifiedFields() {
        var request = MAPPER.readValue("""
            {"cohortId":6,"name":"홍길동","email":"hong@example.test","phone":"010-1234-5678",
            "birthDate":"2002-03-15","university":"테스트대학교","major":"소프트웨어학부","studentNo":"20201234"}
            """, ScholarCreateRequest.class);

        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
        assertThat(request.birthDate()).isEqualTo(LocalDate.of(2002, 3, 15));
        assertThat(request.university()).isEqualTo("테스트대학교");
        assertThat(request.studentNo()).isEqualTo("20201234");
    }

    @Test
    void acceptsRequiredFieldsWithoutOptionalFields() {
        var request = MAPPER.readValue(REQUIRED_JSON, ScholarCreateRequest.class);

        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
        assertThat(request.phone()).isNull();
        assertThat(request.university()).isNull();
        assertThat(request.major()).isNull();
        assertThat(request.studentNo()).isNull();
    }

    @Test
    void requiresCohortNameEmailAndBirthDate() {
        var request = MAPPER.readValue("{}", ScholarCreateRequest.class);

        assertThat(FACTORY.getValidator().validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsExactlyInAnyOrder("cohortId", "name", "email", "birthDate");
    }

    @Test
    void rejectsInvalidIdBlankNameInvalidEmailAndOverlongValues() {
        var request = new ScholarCreateRequest(
            0L, " ", "invalid-email", "1".repeat(21), LocalDate.of(2002, 3, 15),
            "가".repeat(101), "가".repeat(101), "1".repeat(31)
        );

        assertThat(FACTORY.getValidator().validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsExactlyInAnyOrder("cohortId", "name", "email", "phone", "university", "major", "studentNo");
    }

    @Test
    void acceptsValuesAtDatabaseLengthLimits() {
        var request = new ScholarCreateRequest(
            6L, "가".repeat(50), "hong@example.test", "1".repeat(20), LocalDate.of(2002, 3, 15),
            "가".repeat(100), "가".repeat(100), "1".repeat(30)
        );

        assertThat(FACTORY.getValidator().validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2002-02-30", "2002/03/15", "invalid-date"})
    void rejectsInvalidBirthDateFormat(String birthDate) {
        String body = REQUIRED_JSON.replace("2002-03-15", birthDate);

        assertThatThrownBy(() -> MAPPER.readValue(body, ScholarCreateRequest.class))
            .isInstanceOf(JacksonException.class);
    }
}

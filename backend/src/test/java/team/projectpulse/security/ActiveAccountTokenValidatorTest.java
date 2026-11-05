package team.projectpulse.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.student.Student;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.user.UserRepository;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student T6; decoder composition is verified with the security wiring. */
class ActiveAccountTokenValidatorTest {
    UserRepository repository = mock(UserRepository.class);
    ActiveAccountTokenValidator validator = new ActiveAccountTokenValidator(repository);

    @Test
    void readsCurrentAccountAgainForPreviouslyIssuedToken() {
        Student student = new Student();
        student.setEnabled(true);
        when(repository.findById(3)).thenReturn(Optional.of(student));
        Jwt jwt = token(3L);
        assertThat(validator.validate(jwt).hasErrors()).isFalse();
        student.setEnabled(false);
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
        verify(repository, times(2)).findById(3);
    }
    @Test
    void disabledInstructorAndMissingAccountAreInvalid() {
        when(repository.findById(3)).thenReturn(Optional.of(new Instructor()), Optional.empty());
        assertThat(validator.validate(token(3)).hasErrors()).isTrue();
        assertThat(validator.validate(token(3)).hasErrors()).isTrue();
    }
    @ParameterizedTest
    @ValueSource(strings = {"invalid", "1.5", "2147483648", "0", "-1"})
    void malformedAccountIdentityFailsClosed(String identity) {
        assertThat(validator.validate(token(identity)).hasErrors()).isTrue();
        verifyNoInteractions(repository);
    }
    @Test
    void missingIdentityFailsClosed() {
        assertThat(validator.validate(Jwt.withTokenValue("token").header("alg", "RS256").subject("account").build()).hasErrors()).isTrue();
        verifyNoInteractions(repository);
    }
    private Jwt token(Object id) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claim("userId", id).build();
    }
}

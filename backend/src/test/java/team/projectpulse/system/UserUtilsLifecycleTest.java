package team.projectpulse.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

class UserUtilsLifecycleTest {
    UserUtils userUtils = new UserUtils(null, null);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void anonymousIdentityAndRoleFailClosed() {
        assertThat(userUtils.getUserId()).isNull();
        assertThat(userUtils.hasRole("ROLE_instructor")).isFalse();
    }
    @ParameterizedTest @ValueSource(strings = {"invalid", "2147483648"})
    void malformedIdentityFailsClosed(String id) {
        authenticate(id);
        assertThat(userUtils.getUserId()).isNull();
    }
    @Test void overflowingLongIdentityFailsClosed() {
        authenticate(2147483648L);
        assertThat(userUtils.getUserId()).isNull();
    }
    private void authenticate(Object identity) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").claim("userId", identity).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}

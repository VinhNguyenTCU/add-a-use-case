package team.projectpulse.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.user.UserRepository;

/** Checks current account state; must be composed with the decoder's default validators. */
public class ActiveAccountTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final UserRepository userRepository;

    public ActiveAccountTokenValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object claim = token.getClaim("userId");
        Integer userId;
        try {
            // Integer parsing also refuses fractional numbers and overflowing identities.
            userId = claim == null ? null : Integer.valueOf(claim.toString());
        } catch (NumberFormatException ex) {
            return invalidAccount();
        }
        if (userId == null || userId <= 0 || !this.userRepository.findById(userId)
                .map(user -> user.isEnabled()).orElse(false)) {
            return invalidAccount();
        }
        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult invalidAccount() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The account is unavailable.", null));
    }
}

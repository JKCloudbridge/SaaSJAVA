package spike.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import spike.auth.SpikeSupport.MutableClock;

/** Question 3: can all of a user's stateless tokens be revoked immediately (suspend, password change)? */
class TokenRevocationTest {

    private static RSAKey key;

    @BeforeAll
    static void generateKey() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("spike-key").generate();
    }

    @Test
    void bumpingTheUsersSecurityVersionInvalidatesAllTheirOutstandingJwts() throws Exception {
        LocalCredentialsProvider local = new LocalCredentialsProvider(SpikeSupport.delegatingEncoder(),
                new MutableClock());
        var account = local.register("user-a@example.test", "right-password");
        NimbusJwtDecoder decoder = decoderFor(local, account.userId());

        String token = issue(account.userId(), account.securityVersion());
        Jwt decoded = decoder.decode(token);
        assertThat(decoded.getSubject()).isEqualTo(account.userId());

        account.suspend(); // or: password change, "sign out everywhere"

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("revoked");
        // A token issued after the change carries the new version and works again once the account is usable.
        String fresh = issue(account.userId(), account.securityVersion());
        assertThat(decoder.decode(fresh).getSubject()).isEqualTo(account.userId());
    }

    private static NimbusJwtDecoder decoderFor(LocalCredentialsProvider local, String userId) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new TokenVersionValidator(subject -> subject.equals(userId)
                        ? versionOf(local, userId) : Integer.MAX_VALUE)));
        return decoder;
    }

    private static int versionOf(LocalCredentialsProvider local, String userId) {
        // The spike keeps accounts keyed by identifier; there is exactly one account here.
        return local.find("user-a@example.test").securityVersion();
    }

    private static String issue(String userId, int version) throws Exception {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("ver", version)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

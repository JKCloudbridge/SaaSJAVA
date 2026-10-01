package app.platform.identity.internal;

import app.platform.identity.PlatformAuthenticationProvider;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import app.platformapi.ApiPaths;

/**
 * Wiring of the identity module (ADR-0019 to ADR-0021): the password machinery, the rate limits, the signing keys, the
 * one first-party client of the authorization server and its token generator.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfiguration {

    private static final String CLIENT_NAME = "platform-web";

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    // ---- passwords ----

    @Bean
    PasswordHasher passwordHasher(IdentityProperties properties) {
        return new PasswordHasher(properties.password());
    }

    @Bean
    PasswordPolicy passwordPolicy(IdentityProperties properties, BreachedPasswords breached) {
        return new PasswordPolicy(properties.password(), breached);
    }

    @Bean
    LockoutPolicy lockoutPolicy(IdentityProperties properties) {
        return new LockoutPolicy(properties.lockout());
    }

    // ---- limits ----

    @Bean
    Counters counters(StringRedisTemplate redis, Clock clock, IdentityProperties properties, MeterRegistry meters) {
        return new ResilientCounters(new RedisCounters(redis), new LocalCounters(clock),
                clock, properties.rateLimit().redisPause(), meters);
    }

    @Bean
    SignInLimiter signInLimiter(Counters counters, IdentityProperties properties) {
        return new SignInLimiter(counters, properties.rateLimit());
    }

    // ---- sign-in ----

    @Bean
    PlatformProviderAdapter platformProviderAdapter(List<PlatformAuthenticationProvider> providers, AuthAudit audit) {
        return new PlatformProviderAdapter(providers, audit);
    }

    @Bean
    AuthCookies authCookies(IdentityProperties properties) {
        return new AuthCookies(properties.cookies().secure());
    }

    // ---- the authorization server ----

    @Bean
    SigningKeys signingKeys(IdentityProperties properties) {
        return new SigningKeys(properties.signing());
    }

    /** The published keys (all of them); the library serves their public halves. */
    @Bean
    JWKSource<SecurityContext> jwkSource(SigningKeys keys) {
        return keys.publishedKeys();
    }

    /** Signs with the active key only. */
    @Bean
    JwtEncoder jwtEncoder(SigningKeys keys) {
        return keys.encoder();
    }

    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(JwtEncoder encoder) {
        return new DelegatingOAuth2TokenGenerator(new JwtGenerator(encoder), new OAuth2AccessTokenGenerator(),
                new OAuth2RefreshTokenGenerator());
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .authorizationEndpoint(ApiPaths.OAUTH2_AUTHORIZE)
                .tokenEndpoint(ApiPaths.OAUTH2_TOKEN)
                .tokenRevocationEndpoint(ApiPaths.OAUTH2_REVOKE)
                .jwkSetEndpoint(ApiPaths.OAUTH2_JWKS)
                .build();
    }

    /**
     * The one first-party client, the platform's web app: a public client (it cannot keep a secret), so the
     * authorization code is protected by PKCE, which is required (story S3-SEC-18). Access tokens are opaque references
     * and refresh tokens are replaced on every use. The redirect address registered here is a placeholder: the real
     * one, this host's callback, is checked by {@link CallbackRedirectValidator}.
     */
    @Bean
    RegisteredClientRepository registeredClientRepository(IdentityProperties properties) {
        IdentityProperties.Tokens tokens = properties.tokens();
        RegisteredClient client = RegisteredClient.withId(UUID.nameUUIDFromBytes(CLIENT_NAME.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).toString())
                .clientId(AuthFlow.WebClient.CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://platform.invalid" + ApiPaths.AUTH_CALLBACK)
                .scope(OidcScopes.OPENID)
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(tokens.access())
                        .refreshTokenTimeToLive(tokens.refreshIdle())
                        .authorizationCodeTimeToLive(tokens.authorizationCode())
                        .reuseRefreshTokens(false)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                        .build())
                .build();
        return new InMemoryRegisteredClientRepository(client);
    }
}

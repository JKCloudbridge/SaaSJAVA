package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKMatcher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Story S3-SEC-16: key identifiers, rotation and retirement; keys come from the deployment (files) or, on a developer
 * machine only, from memory; nothing is generated in a deployment; the private half is never published.
 */
class SigningKeysTest {

    @TempDir
    Path folder;

    private Path writeKeyPair(String name) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Files.writeString(folder.resolve(name + ".key"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                StandardCharsets.US_ASCII);
        Files.writeString(folder.resolve(name + ".pub"), pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                StandardCharsets.US_ASCII);
        return folder;
    }

    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private IdentityProperties.Signing.Key key(String id, String name) {
        return new IdentityProperties.Signing.Key(id, folder.resolve(name + ".key").toString(),
                folder.resolve(name + ".pub").toString());
    }

    private static String signedBy(SigningKeys keys) {
        return keys.encoder().encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.ES256).build(),
                JwtClaimsSet.builder().subject("user").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                        .build())).getHeaders().get("kid").toString();
    }

    @Test
    void aDeploymentNeedsAtLeastOneKeyAndNeverGeneratesOne() {
        assertThatThrownBy(() -> new SigningKeys(new IdentityProperties.Signing(false, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one signing key is required");
    }

    @Test
    void anEntryWithoutAnIdentifierIsIgnoredSoAForgottenSettingGivesTheClearMessage() {
        assertThatThrownBy(() -> new SigningKeys(new IdentityProperties.Signing(false,
                List.of(new IdentityProperties.Signing.Key("", "", "")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least one signing key");
    }

    @Test
    void anInMemoryKeyIsGeneratedOnlyWhenAskedForAndSigns() {
        SigningKeys keys = new SigningKeys(new IdentityProperties.Signing(true, List.of()));

        assertThat(signedBy(keys)).isEqualTo(keys.activeKeyId());
    }

    @Test
    void keysFromFilesSignWithTheFirstAndPublishAllOfThemWithoutAnyPrivatePart() throws Exception {
        writeKeyPair("new");
        writeKeyPair("old");
        SigningKeys keys = new SigningKeys(new IdentityProperties.Signing(false,
                List.of(key("key-2026-10", "new"), key("key-2026-04", "old"))));

        assertThat(keys.activeKeyId()).isEqualTo("key-2026-10");
        assertThat(signedBy(keys)).isEqualTo("key-2026-10");
        List<JWK> published = keys.publishedKeys().get(new JWKSelector(new JWKMatcher.Builder().build()), null);
        assertThat(published).extracting(JWK::getKeyID).containsExactlyInAnyOrder("key-2026-10", "key-2026-04");
        JWKSet publicSet = new JWKSet(published).toPublicJWKSet();
        assertThat(publicSet.toString()).doesNotContain("\"d\"");
    }

    @Test
    void retiringAKeyIsRemovingItFromTheListAfterWhichItNoLongerVerifies() throws Exception {
        writeKeyPair("current");
        SigningKeys keys = new SigningKeys(new IdentityProperties.Signing(false, List.of(key("only", "current"))));

        List<JWK> published = keys.publishedKeys().get(new JWKSelector(new JWKMatcher.Builder().build()), null);

        assertThat(published).extracting(JWK::getKeyID).containsExactly("only");
    }

    @Test
    void anUnreadableKeyStopsTheStartWithoutRepeatingThePathOrTheLibrariesText() throws Exception {
        Files.writeString(folder.resolve("bad.key"), "not a key", StandardCharsets.US_ASCII);
        Files.writeString(folder.resolve("bad.pub"), "not a key", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> new SigningKeys(new IdentityProperties.Signing(false, List.of(key("bad", "bad")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'bad'")
                .hasMessageNotContaining(folder.toString());
    }
}

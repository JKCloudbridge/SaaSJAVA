package app.platform.identity.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The keys that sign ID tokens (story S3-SEC-16, ADR-0019). Elliptic-curve P-256 key pairs, each with an identifier
 * that is written into every token it signs and published with its public half, so a verifier finds the right key.
 *
 * <p><strong>Where keys come from.</strong> A deployment supplies them as files (a PKCS#8 private key and an X.509
 * public key, both PEM) mounted from its secret store; with none the application refuses to start. A developer machine
 * and the tests may set {@code platform.identity.signing.ephemeral=true}: one key is generated at start and lives only
 * in memory (nothing is written anywhere, so no key can end up in the repository). No key is ever kept in a database
 * table or in metadata (ADR-0007); a secrets-store implementation of this class arrives with Sprint 27.
 *
 * <p><strong>Rotation and retirement.</strong> The first configured key signs; every configured key is published and so
 * can verify. To rotate: add the new key at the front of the list, deploy; once no token signed by the old key can
 * still be valid (the longest ID token life, minutes), remove it from the list. Because no instance generates keys in a
 * deployment, all instances always sign and verify with the same ones.
 */
final class SigningKeys {

    private static final Logger LOG = LoggerFactory.getLogger(SigningKeys.class);

    private final List<ECKey> keys;

    SigningKeys(IdentityProperties.Signing settings) {
        List<ECKey> loaded = new ArrayList<>();
        if (settings.ephemeral()) {
            loaded.add(generate());
            LOG.info("An in-memory token signing key was generated; it is forgotten when the application stops");
        } else {
            for (IdentityProperties.Signing.Key key : settings.keys()) {
                if (key.id() != null && !key.id().isBlank()) {
                    loaded.add(read(key));
                }
            }
            if (loaded.isEmpty()) {
                throw new IllegalStateException("platform.identity.signing.keys: at least one signing key is required "
                        + "(or platform.identity.signing.ephemeral=true on a developer machine)");
            }
        }
        this.keys = List.copyOf(loaded);
    }

    /** The identifier of the key that signs new tokens. */
    String activeKeyId() {
        return keys.get(0).getKeyID();
    }

    /** The public keys of all configured keys, for the published key set. */
    JWKSource<SecurityContext> publishedKeys() {
        return new ImmutableJWKSet<>(new JWKSet(new ArrayList<>(keys)));
    }

    /** The encoder that signs with the active key only (the library refuses to pick between several). */
    JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.get(0))));
    }

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).keyID(UUID.randomUUID().toString())
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("A signing key could not be generated", e);
        }
    }

    private static ECKey read(IdentityProperties.Signing.Key key) {
        if (key.id() == null || key.id().isBlank() || key.privateKeyFile() == null || key.publicKeyFile() == null) {
            throw new IllegalStateException(
                    "platform.identity.signing.keys: every key needs id, private-key-file and public-key-file");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            ECPrivateKey privateKey = (ECPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(pem(Path.of(key.privateKeyFile()))));
            ECPublicKey publicKey = (ECPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(pem(Path.of(key.publicKeyFile()))));
            return new ECKey.Builder(Curve.P_256, publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE)
                    .keyID(key.id()).build();
        } catch (IOException | GeneralSecurityException | ClassCastException e) {
            // Neither the path nor the library's message is repeated: they may help an attacker, not the operator.
            throw new IllegalStateException("The signing key '" + key.id() + "' could not be read as a P-256 key pair "
                    + "(PKCS#8 private key and X.509 public key, PEM)", e);
        }
    }

    private static byte[] pem(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.US_ASCII);
        String body = text.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}

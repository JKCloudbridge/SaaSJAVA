package app.platform.sharedkernel.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A mail request holds facts, never a secret, a link or a finished message (ADR-0024). */
class MailRequestTest {

    @Test
    void aRequestKeepsItsFacts() {
        UUID user = UUID.randomUUID();

        MailRequest request = MailRequest.of(MailTemplate.ACCOUNT_LOCKED, "a@example.test").forUser(user)
                .with("reason", "too_many_failures");

        assertThat(request.template()).isEqualTo(MailTemplate.ACCOUNT_LOCKED);
        assertThat(request.userId()).isEqualTo(user);
        assertThat(request.variables()).containsExactly(Map.entry("reason", "too_many_failures"));
    }

    @Test
    void aVariableNamedLikeASecretOrALinkIsRefused() {
        MailRequest request = MailRequest.of(MailTemplate.SIGN_UP_REQUEST, "a@example.test");

        for (String name : new String[] {"token", "reset_token", "password", "link", "sign_up_url", "secret_word",
            "authorization", "code"}) {
            assertThatThrownBy(() -> request.with(name, "x")).as(name).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anAddressMustBeNormalizedAndVariablesAreBounded() {
        assertThatThrownBy(() -> MailRequest.of(MailTemplate.SIGN_UP_REQUEST, " a@example.test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailRequest.of(MailTemplate.SIGN_UP_REQUEST, "a"))
                .isInstanceOf(IllegalArgumentException.class);
        MailRequest request = MailRequest.of(MailTemplate.SIGN_UP_REQUEST, "a@example.test")
                .with("note", "x".repeat(500));
        assertThat(request.variables().get("note")).hasSize(MailRequest.MAX_VALUE_LENGTH);
    }

    @Test
    void theTextFormNeverShowsTheAddress() {
        assertThat(MailRequest.of(MailTemplate.PASSWORD_RESET_REQUEST, "a@example.test").toString())
                .doesNotContain("a@example.test");
    }
}

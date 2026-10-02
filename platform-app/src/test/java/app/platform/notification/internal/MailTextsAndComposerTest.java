package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.identity.AccountTokenPurpose;
import app.platform.identity.AccountTokens;
import app.platform.identity.InvitationMail;
import app.platform.identity.Invitations;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.notification.internal.MailComposer.Decision;
import app.platform.notification.internal.MailStore.ClaimedMail;
import app.platform.sharedkernel.mail.MailTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** The words of the mails, the decision of what a queued mail becomes, the retry delay and the settings checks. */
class MailTextsAndComposerTest {

    private static final MailProperties PROPERTIES = properties("no-reply@example.test", "http://localhost:3000");

    private final Users users = mock(Users.class);
    private final AccountTokens tokens = mock(AccountTokens.class);
    private final Invitations invitations = mock(Invitations.class);
    private final MailComposer composer = new MailComposer(users, invitations, tokens, new MailLinks(PROPERTIES));

    private static MailProperties properties(String sender, String baseUrl) {
        return new MailProperties(true, sender, baseUrl, Duration.ofSeconds(2), 10, Duration.ofMinutes(2), 10,
                Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofHours(24), Duration.ofDays(7),
                Duration.ofDays(30));
    }

    private static ClaimedMail mail(MailTemplate template, String email) {
        return new ClaimedMail(UUID.randomUUID(), template, email, null, Map.of(), Instant.now(), 1, 1);
    }

    private static User user(String email, UserStatus status) {
        return new User(UUID.randomUUID(), email, "Person A", status, 0, null, 0);
    }

    // ---- texts ----

    @Test
    void everyMessageIsFilledCompletelyAndHasBothForms() {
        Map<String, String> values = Map.of("link", "http://localhost:3000/x#token=abc", "lifetime", "24 hours",
                "signInLink", "http://localhost:3000/sign-in", "forgotLink", "http://localhost:3000/forgot-password",
                "organization", "Organization A");
        for (MailTexts.Kind kind : MailTexts.Kind.values()) {
            assertThat(MailTexts.text(kind, values)).as(kind + " text").doesNotContain("{{").isNotBlank();
            assertThat(MailTexts.html(kind, values)).as(kind + " html").doesNotContain("{{").startsWith("<!doctype");
            assertThat(kind.subject()).isNotBlank().doesNotContain("\n");
        }
    }

    @Test
    void theHtmlFormEscapesEveryValueAndTurnsAddressesIntoLinks() {
        Map<String, String> values = Map.of("link", "http://localhost:3000/x#token=abc\"><script>x</script>",
                "lifetime", "<b>24</b> hours");

        String html = MailTexts.html(MailTexts.Kind.SIGN_UP_LINK, values);

        assertThat(html).doesNotContain("<script>").doesNotContain("<b>24</b>").contains("&lt;b&gt;24&lt;/b&gt;");
        assertThat(MailTexts.text(MailTexts.Kind.SIGN_UP_LINK, values)).contains("<script>");
    }

    @Test
    void lifetimesAreWrittenTheWayAPersonReadsThem() {
        assertThat(MailComposer.describe(Duration.ofHours(24))).isEqualTo("24 hours");
        assertThat(MailComposer.describe(Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(MailComposer.describe(Duration.ofMinutes(60))).isEqualTo("1 hour");
        assertThat(MailComposer.describe(Duration.ofMinutes(90))).isEqualTo("90 minutes");
        assertThat(MailComposer.describe(Duration.ofMinutes(1))).isEqualTo("1 minute");
        assertThat(MailComposer.describe(Duration.ofDays(7))).isEqualTo("7 days");
    }

    @Test
    void anOrganizationNameInAMailCanOnlyBeAName() {
        assertThat(MailTexts.safeName("Organization A")).isEqualTo("Organization A");
        assertThat(MailTexts.safeName("https://evil.example/sign-in now")).doesNotContain(":").doesNotContain("/")
                .doesNotContain(".");
        assertThat(MailTexts.safeName("a@b.example")).doesNotContain("@").doesNotContain(".");
        assertThat(MailTexts.safeName("Line one\nLine two\r\nLine three")).doesNotContain("\n").doesNotContain("\r");
        assertThat(MailTexts.safeName("<script>alert(1)</script>")).doesNotContain("<").doesNotContain(">");
        assertThat(MailTexts.safeName("x".repeat(500))).hasSize(80);
        assertThat(MailTexts.safeName("///:::")).isEqualTo("an organization");
        // And the HTML form of a message never turns the name into a link, whatever it started with.
        String html = MailTexts.html(MailTexts.Kind.INVITATION_NEW, Map.of("link", "http://localhost:3000/x#token=a",
                "lifetime", "7 days", "organization", MailTexts.safeName("https://evil.example")));
        assertThat(html).doesNotContain("evil.example").doesNotContain("href=\"https://evil");
    }

    // ---- decisions ----

    @Test
    void aSignUpForAnUnknownAddressBecomesALinkMessageWithAFreshTokenOnThePlatformHost() {
        when(users.findByEmail("a@example.test")).thenReturn(Optional.empty());
        when(tokens.issue(AccountTokenPurpose.SIGN_UP, "a@example.test", null)).thenReturn("TOKEN123");
        when(tokens.lifetime(AccountTokenPurpose.SIGN_UP)).thenReturn(Duration.ofHours(24));

        Decision decision = composer.decide(mail(MailTemplate.SIGN_UP_REQUEST, "a@example.test"));

        Decision.Send send = (Decision.Send) decision;
        assertThat(send.outcome()).isEqualTo("link_sent");
        assertThat(send.message().to()).isEqualTo("a@example.test");
        assertThat(send.message().text()).contains("http://localhost:3000/sign-up/complete#token=TOKEN123")
                .contains("24 hours");
    }

    @Test
    void aSignUpForAnActiveAccountBecomesANoticeWithoutAToken() {
        when(users.findByEmail("a@example.test")).thenReturn(Optional.of(user("a@example.test", UserStatus.ACTIVE)));

        Decision.Send send = (Decision.Send) composer.decide(mail(MailTemplate.SIGN_UP_REQUEST, "a@example.test"));

        assertThat(send.outcome()).isEqualTo("account_exists");
        assertThat(send.message().text()).doesNotContain("#token=");
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void aSignUpForAnyOtherStateSendsNothing() {
        for (UserStatus status : new UserStatus[] {UserStatus.INVITED, UserStatus.SUSPENDED,
            UserStatus.DEACTIVATED}) {
            when(users.findByEmail("a@example.test")).thenReturn(Optional.of(user("a@example.test", status)));

            Decision decision = composer.decide(mail(MailTemplate.SIGN_UP_REQUEST, "a@example.test"));

            assertThat(decision).isEqualTo(new Decision.Suppress("account_not_active"));
        }
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void aResetBecomesALinkOnlyForAnActiveAccount() {
        User active = user("a@example.test", UserStatus.ACTIVE);
        when(users.findByEmail("a@example.test")).thenReturn(Optional.of(active));
        when(tokens.issue(AccountTokenPurpose.PASSWORD_RESET, "a@example.test", active.id())).thenReturn("RESET9");
        when(tokens.lifetime(AccountTokenPurpose.PASSWORD_RESET)).thenReturn(Duration.ofMinutes(60));
        when(users.findByEmail("none@example.test")).thenReturn(Optional.empty());
        when(users.findByEmail("off@example.test")).thenReturn(Optional.of(user("off@example.test",
                UserStatus.SUSPENDED)));

        Decision.Send send = (Decision.Send) composer.decide(mail(MailTemplate.PASSWORD_RESET_REQUEST,
                "a@example.test"));

        assertThat(send.message().text()).contains("http://localhost:3000/reset-password#token=RESET9")
                .contains("1 hour");
        assertThat(composer.decide(mail(MailTemplate.PASSWORD_RESET_REQUEST, "none@example.test")))
                .isEqualTo(new Decision.Suppress("no_account"));
        assertThat(composer.decide(mail(MailTemplate.PASSWORD_RESET_REQUEST, "off@example.test")))
                .isEqualTo(new Decision.Suppress("account_not_active"));
    }

    @Test
    void theNoticesAreSentAsQueuedAndPointToTheForgottenPasswordPage() {
        for (MailTemplate template : new MailTemplate[] {MailTemplate.PASSWORD_CHANGED,
            MailTemplate.ACCOUNT_LOCKED}) {
            Decision.Send send = (Decision.Send) composer.decide(mail(template, "a@example.test"));

            assertThat(send.outcome()).isEqualTo("notice_sent");
            assertThat(send.message().text()).contains("http://localhost:3000/forgot-password")
                    .doesNotContain("#token=");
        }
    }

    @Test
    void anInvitationBecomesALinkMessageInTheWordsOfTheAccountState() {
        UUID tenant = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();
        when(tokens.issueInvitation("a@example.test", tenant, invitation)).thenReturn("INVITE7");
        when(tokens.lifetime(AccountTokenPurpose.INVITATION)).thenReturn(Duration.ofDays(7));
        ClaimedMail queued = new ClaimedMail(UUID.randomUUID(), MailTemplate.INVITATION, "a@example.test", null,
                Map.of("organization_id", tenant.toString(), "invitation_id", invitation.toString()),
                Instant.now(), 1, 1);

        when(invitations.forMail(tenant, invitation))
                .thenReturn(Optional.of(new InvitationMail("Organization A", "a@example.test", false)));
        Decision.Send fresh = (Decision.Send) composer.decide(queued);
        when(invitations.forMail(tenant, invitation))
                .thenReturn(Optional.of(new InvitationMail("Organization A", "a@example.test", true)));
        Decision.Send existing = (Decision.Send) composer.decide(queued);

        assertThat(fresh.message().text()).contains("http://localhost:3000/invitations/accept#token=INVITE7")
                .contains("\"Organization A\"").contains("7 days").contains("choose your name and a password");
        assertThat(existing.message().text()).contains("#token=INVITE7").contains("sign in with that account");
        assertThat(fresh.message().subject()).isEqualTo(existing.message().subject())
                .as("the subject holds no text an administrator chose").doesNotContain("Organization");
    }

    @Test
    void anInvitationThatIsNotOpenAnyMoreOrCannotBeUnderstoodSendsNothingAndCreatesNoToken() {
        UUID tenant = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();
        when(invitations.forMail(tenant, invitation)).thenReturn(Optional.empty());

        assertThat(composer.decide(new ClaimedMail(UUID.randomUUID(), MailTemplate.INVITATION, "a@example.test", null,
                Map.of("organization_id", tenant.toString(), "invitation_id", invitation.toString()), Instant.now(),
                1, 1))).isEqualTo(new Decision.Suppress("invitation_not_open"));
        assertThat(composer.decide(mail(MailTemplate.INVITATION, "a@example.test")))
                .isEqualTo(new Decision.Suppress("invitation_unknown"));
        verify(tokens, never()).issueInvitation(any(), any(), any());
    }

    // ---- retry delay ----

    @Test
    void theDelayDoublesWithEachFailureAndStopsAtTheCap() {
        MailBackoff backoff = new MailBackoff(Duration.ofSeconds(30), Duration.ofMinutes(15),
                RandomGenerator.getDefault());

        assertThat(backoff.delayAfter(1)).isBetween(Duration.ofSeconds(24), Duration.ofSeconds(36));
        assertThat(backoff.delayAfter(2)).isBetween(Duration.ofSeconds(48), Duration.ofSeconds(72));
        assertThat(backoff.delayAfter(4)).isBetween(Duration.ofSeconds(192), Duration.ofSeconds(288));
        assertThat(backoff.delayAfter(30)).isLessThanOrEqualTo(Duration.ofMinutes(15));
        assertThat(backoff.delayAfter(1000)).isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    // ---- settings ----

    @Test
    void theSettingsRefuseWhatWouldSendMailsFromOrToTheWrongPlace() {
        assertThat(properties("no-reply@example.test", "https://platform.example.test").baseUrl())
                .isEqualTo("https://platform.example.test");
        assertThatThrownBy(() -> properties("no-reply@example.test", "http://platform.example.org"))
                .as("plain http away from a developer machine").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("no-reply@example.test", "https://platform.example.test/app"))
                .as("a path").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("no-reply@example.test", "https://user:pw@platform.example.test"))
                .as("credentials in the address").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("no-reply@example.test", ""))
                .as("no address").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("not an address", "https://platform.example.test"))
                .as("a bad sender").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(null, "https://platform.example.test"))
                .as("no sender").isInstanceOf(IllegalArgumentException.class);
    }
}

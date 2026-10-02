package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMail;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.awaitility.Awaitility;

/**
 * Shared steps of the sign-up, reset and mail tests: the four requests as a browser sends them, driving the relay, and
 * waiting for mail in the catcher. Test data uses generic example addresses only.
 */
final class FlowSupport {

    private FlowSupport() {
    }

    static String newAddress() {
        return "person-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.test";
    }

    static String strongPassword() {
        return "pw-" + UUID.randomUUID() + "-" + UUID.randomUUID();
    }

    static Response requestSignUp(TestBrowser browser, String email) {
        return browser.postJson("/api/v1/auth/sign-up", "{\"email\":\"" + email + "\"}");
    }

    static Response completeSignUp(TestBrowser browser, String token, String name, String password) {
        return browser.postJson("/api/v1/auth/sign-up/complete", "{\"token\":\"" + token + "\",\"displayName\":\""
                + name + "\",\"password\":\"" + password + "\"}");
    }

    static Response requestReset(TestBrowser browser, String email) {
        return browser.postJson("/api/v1/auth/password/forgot", "{\"email\":\"" + email + "\"}");
    }

    static Response completeReset(TestBrowser browser, String token, String newPassword) {
        return browser.postJson("/api/v1/auth/password/reset",
                "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    /** Lets the relay handle everything that is due, the way its poll loop does. */
    static void drain(MailRelay relay) {
        int claimed;
        do {
            claimed = relay.pollOnce();
        } while (claimed > 0);
    }

    /** Waits until the catcher holds exactly this many messages for the address, and returns them. */
    static List<TestMail.Message> awaitMails(String address, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> TestMail.to(address).size() >= count);
        List<TestMail.Message> mails = TestMail.to(address);
        assertThat(mails).as("messages for " + address).hasSize(count);
        return mails;
    }

    /** Everything a caller can see of a response except what is unique per request. */
    static String observable(Response response) {
        Map<String, String> headers = new TreeMap<>();
        response.headers().forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (!lower.equals("date") && !lower.equals("x-request-id") && !lower.equals("x-trace-id")
                    && !lower.equals("set-cookie") && !lower.equals("content-length")) {
                headers.put(lower, String.join("|", values));
            }
        });
        String body = response.body().replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"-\"")
                .replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"-\"");
        return response.status() + " " + headers + " " + body;
    }
}

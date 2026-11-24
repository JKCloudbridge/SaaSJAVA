package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.awaitility.Awaitility;

/**
 * What the tests of the webhooks share (Sprint 32): an organization on a plan that has the feature, the short ways to
 * make, verify and switch on a subscription through the real management API, to read its delivery log, and to wait
 * for something to happen in another thread. Names and addresses are generic: the receiver is a service the test
 * starts on this machine.
 */
abstract class WebhookSupport extends ClientSupport {

    protected static final String WEBHOOKS = "/api/v1/webhooks";
    protected static final String EVENTS_CREATED = "[\"record.created\"]";
    protected static final String EVENTS_ALL = "[\"record.created\",\"record.updated\",\"record.deleted\","
            + "\"record.restored\"]";

    /** What making a subscription answered: its identifier and the secret that is shown once. */
    protected record Hook(String id, String secret, Response response) {

        /** The identifier without the secret, for a test that must never print it. */
        @Override
        public String toString() {
            return "Hook[" + id + "]";
        }
    }

    protected static String hookName() {
        return "hook-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String saveBody(String name, String address, String eventsJson, String objectsJson,
            long version) {
        return "{\"name\":\"" + name + "\",\"address\":\"" + address + "\",\"events\":" + eventsJson
                + (objectsJson == null ? "" : ",\"objects\":" + objectsJson) + ",\"version\":" + version + "}";
    }

    /** Makes a subscription as the person behind the browser; it must succeed and starts switched off. */
    protected Hook create(TestBrowser browser, String name, String address, String eventsJson, String objectsJson) {
        Response response = browser.postJson(WEBHOOKS, saveBody(name, address, eventsJson, objectsJson, 0));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.data.detail.state")).isEqualTo("DISABLED");
        return new Hook(JsonPath.read(response.body(), "$.data.detail.id"),
                JsonPath.read(response.body(), "$.data.secret"), response);
    }

    protected Hook create(TestBrowser browser, WebhookReceiver receiver, String eventsJson) {
        return create(browser, hookName(), receiver.address("/hooks"), eventsJson, null);
    }

    protected static Response read(TestBrowser browser, Hook hook) {
        return browser.get(WEBHOOKS + "/" + hook.id());
    }

    protected static long versionOf(TestBrowser browser, Hook hook) {
        return JsonPath.<Number>read(read(browser, hook).body(), "$.data.version").longValue();
    }

    protected static Response sendTest(TestBrowser browser, Hook hook) {
        return browser.postJson(WEBHOOKS + "/" + hook.id() + "/test", "{}");
    }

    protected static Response switchOn(TestBrowser browser, Hook hook) {
        return browser.postJson(WEBHOOKS + "/" + hook.id() + "/enable",
                "{\"version\":" + versionOf(browser, hook) + "}");
    }

    protected static Response switchOff(TestBrowser browser, Hook hook) {
        return browser.postJson(WEBHOOKS + "/" + hook.id() + "/disable", "{}");
    }

    protected static Response rotateSecret(TestBrowser browser, Hook hook, int overlapHours) {
        return browser.postJson(WEBHOOKS + "/" + hook.id() + "/secret/rotate",
                "{\"overlapHours\":" + overlapHours + "}");
    }

    /**
     * A subscription that is verified and switched on, the way a person does it: made, sent a test, switched on. The
     * ping the test made is forgotten so that the receiver's list starts with the first real delivery.
     */
    protected Hook live(TestBrowser browser, WebhookReceiver receiver, String eventsJson) {
        Hook hook = create(browser, receiver, eventsJson);
        Response tested = sendTest(browser, hook);
        assertThat(tested.status()).as(tested.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(tested.body(), "$.data.delivered")).as(tested.body()).isTrue();
        Response on = switchOn(browser, hook);
        assertThat(on.status()).as(on.body()).isEqualTo(200);
        receiver.clear();
        return hook;
    }

    /** A hook with a different secret number to read, for rotation tests: the secret that is current now. */
    protected static int secretNumber(TestBrowser browser, Hook hook) {
        return JsonPath.<Number>read(read(browser, hook).body(), "$.data.secretNumber").intValue();
    }

    // ---- the delivery log ----

    protected static Response deliveries(TestBrowser browser, Hook hook, String... states) {
        String filter = states.length == 0 ? "" : "?" + String.join("&", Arrays.stream(states)
                .map(state -> "state=" + state).toList());
        return browser.get(WEBHOOKS + "/" + hook.id() + "/deliveries" + filter);
    }

    protected static int count(TestBrowser browser, Hook hook, String state) {
        return JsonPath.<List<?>>read(deliveries(browser, hook, state).body(), "$.data.items").size();
    }

    protected static String firstDelivery(TestBrowser browser, Hook hook, String state) {
        return JsonPath.read(deliveries(browser, hook, state).body(), "$.data.items[0].id");
    }

    protected static Response attemptsOf(TestBrowser browser, Hook hook, String delivery) {
        return browser.get(WEBHOOKS + "/" + hook.id() + "/deliveries/" + delivery + "/attempts");
    }

    // ---- records through the public door ----

    protected String createdEmployee(Caller program, String name) {
        return newEmployee(program, name, "Sales");
    }

    // ---- waiting ----

    protected static void await(String what, BooleanSupplier condition) {
        Awaitility.await(what).atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .until(condition::getAsBoolean);
    }

    /** What the JSON holds at the path, or null when the answer leaves it out (an absent value is not written). */
    protected static <T> T optional(String body, String path) {
        try {
            return JsonPath.read(body, path);
        } catch (com.jayway.jsonpath.PathNotFoundException absent) {
            return null;
        }
    }

    /** An organization with the feature, the employee object and an administrator; the shape most tests start from. */
    protected Organization webhookOrganization() {
        return organizationWithClients();
    }
}

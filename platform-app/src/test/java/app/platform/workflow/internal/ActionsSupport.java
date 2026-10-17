package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the Sprint 23 tests of actions, retries, dead letters, replay and loops share (ADR-0138 to ADR-0143): the JSON
 * of the actions that change records or queue a mail, short ways to read what a record holds through the real API,
 * the history and the dead letters, and a look at the platform-level tables (the mail queue) that no organization
 * context can see. Generic data only.
 */
abstract class ActionsSupport extends WorkflowSupport {

    protected static final String PARKED = "/api/v1/workflows/parked-events";

    protected static final String PROFILES = "/api/v1/profiles";
    protected static final String MEMBERS = "/api/v1/members";

    // ---- people ----

    protected TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    /** A member who holds the abilities through a profile of their own. */
    protected Member memberWith(Organization organization, TestBrowser admin, String... abilities) {
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        List<String> quoted = Arrays.stream(abilities).map(ability -> "\"" + ability + "\"").toList();
        Response profile = admin.postJson(PROFILES, "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[" + String.join(",", quoted) + "]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        Response set = admin.request("PUT", MEMBERS + "/" + person.membership() + "/profile",
                "{\"profileId\":\"" + JsonPath.<String>read(profile.body(), "$.data.id") + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        return person;
    }

    // ---- actions ----

    /** A value written here, for an assignment. */
    protected static String value(String field, String text) {
        return "{\"field\":\"" + field + "\",\"value\":\"" + text + "\"}";
    }

    /** A copy of a field of the record that started the workflow, for an assignment. */
    protected static String copy(String field, String from) {
        return "{\"field\":\"" + field + "\",\"fromField\":\"" + from + "\"}";
    }

    protected static String update(String name, String... assignments) {
        return "{\"type\":\"UPDATE_RECORD\",\"name\":\"" + name + "\",\"settings\":{},\"assignments\":["
                + String.join(",", assignments) + "]}";
    }

    protected static String updateWithAttempts(String name, int attempts, String... assignments) {
        return "{\"type\":\"UPDATE_RECORD\",\"name\":\"" + name + "\",\"settings\":{},\"maxAttempts\":" + attempts
                + ",\"assignments\":[" + String.join(",", assignments) + "]}";
    }

    protected static String create(String name, String object, String linkField, String... assignments) {
        return "{\"type\":\"CREATE_RECORD\",\"name\":\"" + name + "\",\"settings\":{\"object\":\"" + object + "\""
                + (linkField == null ? "" : ",\"linkField\":\"" + linkField + "\"") + "},\"assignments\":["
                + String.join(",", assignments) + "]}";
    }

    protected static String mail(String name, String recipientField) {
        return "{\"type\":\"SEND_EMAIL\",\"name\":\"" + name + "\",\"settings\":{\"recipientField\":\""
                + recipientField + "\",\"template\":\"RECORD_NOTICE\"}}";
    }

    // ---- fields and rules ----

    protected static void addField(TestBrowser admin, String object, String name, String type, String extra) {
        Response created = admin.postJson(METADATA_OBJECTS + "/" + object + "/fields", "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"type\":\"" + type + "\"" + extra + "}");
        assertThat(created.status()).as(name + ": " + created.body()).isEqualTo(201);
    }

    protected static void addRule(TestBrowser admin, String name, String expression, String message) {
        Response created = admin.postJson(METADATA_OBJECTS + "/" + OBJECT + "/validation-rules", "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"expression\":\"" + expression + "\",\"errorMessage\":\"" + message
                + "\"}");
        assertThat(created.status()).as(name + ": " + created.body()).isEqualTo(201);
    }

    /** The version of a field of the sample object, as the object manager shows it. */
    protected static long fieldVersion(TestBrowser admin, String field) {
        return JsonPath.<List<Number>>read(admin.get(METADATA_OBJECTS + "/" + OBJECT).body(),
                "$.data.fields[?(@.apiName=='" + field + "')].version").get(0).longValue();
    }

    /** Makes a field of the sample object required, or unique. */
    protected static void constrain(TestBrowser admin, String field, boolean required, boolean unique) {
        Response changed = admin.request("PUT", METADATA_OBJECTS + "/" + OBJECT + "/fields/" + field,
                "{\"label\":\"" + field + "\",\"description\":\"\",\"required\":" + required + ",\"unique\":"
                        + unique + ",\"version\":" + fieldVersion(admin, field) + "}");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
    }

    protected static void removeRule(TestBrowser admin, String name) {
        Response gone = admin.request("DELETE", METADATA_OBJECTS + "/" + OBJECT + "/validation-rules/" + name, null);
        assertThat(gone.status()).as(gone.body()).isBetween(200, 204);
    }

    // ---- what a record holds, what ran ----

    /** The field of a record, as the API shows it to the administrator. */
    protected static Object fieldOf(TestBrowser admin, String id, String field) {
        return JsonPath.read(admin.get(RECORDS + "/" + id).body(), "$.data.fields." + field);
    }

    protected static long versionOfRecord(TestBrowser admin, String id) {
        return JsonPath.<Number>read(admin.get(RECORDS + "/" + id).body(), "$.data.version").longValue();
    }

    /** The one execution of the organization (its id), asserting there is exactly one. */
    protected static String theExecution(Organization organization) {
        List<Map<String, String>> found = rows(organization, "select id::text as id from workflow_execution "
                + "where replay_of is null order by created_at, id");
        assertThat(found).hasSize(1);
        return found.get(0).get("id");
    }

    protected static Map<String, String> execution(Organization organization, String id) {
        return rows(organization, "select id::text as id, status, reason, failure_type, attempts, replay_of::text "
                + "as replay_of, replay_generation, resume_step, chain_depth, "
                + "case when run_at > now() then 'later' else 'now' end as due, "
                + "case when locked_until is null then 'free' else 'held' end as lease "
                + "from workflow_execution where id = cast(? as uuid)", id).get(0);
    }

    /** The steps of an execution: name:status:attempts, in order. */
    protected static List<String> steps(Organization organization, String id) {
        return rows(organization, "select name, status, attempts from workflow_step where execution_id = "
                + "cast(? as uuid) order by position", id).stream()
                .map(row -> row.get("name") + ":" + row.get("status") + ":" + row.get("attempts")).toList();
    }

    /** Makes a waiting execution due at once, as time passing would. */
    protected static void makeDue(Organization organization) {
        run(organization, "update workflow_execution set run_at = now() - interval '1 second', "
                + "version = version + 1 where status = 'QUEUED'");
    }

    protected static void spendTheWait(Organization organization, String execution) {
        run(organization, "update workflow_execution set run_at = now() - interval '1 second', version = version + 1 "
                + "where id = cast(? as uuid)", execution);
    }

    /** Seconds from now until the execution is due (negative when it is due). */
    protected static double secondsUntilDue(Organization organization, String execution) {
        return Double.parseDouble(rows(organization, "select extract(epoch from run_at - now()) as seconds from "
                + "workflow_execution where id = cast(? as uuid)", execution).get(0).get("seconds"));
    }

    // ---- the platform-level tables ----

    /** The variables of every queued mail to the address, each as the text of its JSON. */
    protected static List<String> queuedMails(String address) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(
                        "select variables::text from mail_queue where email = ? and template = 'RECORD_NOTICE' "
                                + "order by created_at")) {
            statement.setString(1, address);
            try (ResultSet result = statement.executeQuery()) {
                List<String> found = new java.util.ArrayList<>();
                while (result.next()) {
                    found.add(result.getString(1));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    /** The first column of every row of a query run as the owner of the database (outbox, audit and mail tables). */
    protected static List<String> owner(String sql, Object... parameters) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                List<String> found = new java.util.ArrayList<>();
                while (result.next()) {
                    found.add(result.getString(1));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    protected static String newUuid() {
        return UUID.randomUUID().toString();
    }
}

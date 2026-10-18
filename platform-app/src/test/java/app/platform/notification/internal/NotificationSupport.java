package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import app.platform.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the integration tests of the notification module share (ADR-0154 to ADR-0158): organizations with an
 * administrator and plain members, a way to make a notice through the very contract the other modules use (in the
 * organization's own context and a transaction, as a handler or an action would), short ways to read the member's side
 * through the real API, and ways to look at the rows. Generic data only.
 */
abstract class NotificationSupport {

    protected static final String NOTIFICATIONS = "/api/v1/notifications";
    protected static final String PREFERENCES = "/api/v1/notification-preferences";
    protected static final String TEMPLATES = "/api/v1/metadata/notification-templates";

    @LocalServerPort
    protected int port;

    @Autowired
    protected Users users;

    @Autowired
    protected Plans plans;

    @Autowired
    protected Subscriptions subscriptions;

    @Autowired
    protected Notices notices;

    @Autowired
    protected TenantContexts contexts;

    @Autowired
    protected TransactionTemplate transaction;

    @Autowired
    protected NoticeStore store;

    protected Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3, "workflows", "approvals");
        return organization;
    }

    /** A plain member (the Member profile, no abilities) of the organization. */
    protected Member member(Organization organization) {
        return TestOrganizations.join(users, organization.tenant(), false);
    }

    protected TestBrowser browser(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    protected TestBrowser adminOf(Organization organization) {
        return browser(organization, organization.admin());
    }

    // ---- making a notice the way the other modules do ----

    protected Notices.Result notify(Organization organization, UUID recipient, NoticeKind kind, String key,
            Map<String, String> facts) {
        return contexts.call(TenantContext.of(organization.id()), () -> transaction.execute(status ->
                notices.notify(new NoticeRequest(kind, recipient, null, null, facts, key))));
    }

    protected Notices.Result notifyAboutRecord(Organization organization, UUID recipient, NoticeKind kind, String key,
            Map<String, String> facts, String object, UUID record) {
        return contexts.call(TenantContext.of(organization.id()), () -> transaction.execute(status ->
                notices.notify(new NoticeRequest(kind, recipient, object, record, facts, key))));
    }

    protected Notices.Result workflowNotice(Organization organization, Member recipient, String key) {
        return notify(organization, recipient.person().user().id(), NoticeKind.WORKFLOW_NOTICE, key,
                Map.of("workflow", "Remind__c"));
    }

    // ---- reading as a member ----

    protected static List<String> titles(TestBrowser browser) {
        return JsonPath.read(browser.get(NOTIFICATIONS).body(), "$.data[*].title");
    }

    protected static int unread(TestBrowser browser) {
        Response summary = browser.get(NOTIFICATIONS + "/summary");
        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        return JsonPath.<Number>read(summary.body(), "$.data.unread").intValue();
    }

    protected static List<String> idsOf(TestBrowser browser) {
        return JsonPath.read(browser.get(NOTIFICATIONS).body(), "$.data[*].id");
    }

    // ---- looking at the rows ----

    protected static long scalar(Organization organization, String sql, Object... parameters) {
        try {
            return TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.count(connection, sql,
                    parameters));
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    protected static void run(Organization organization, String sql, Object... parameters) {
        try {
            TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection, sql,
                    parameters));
        } catch (SQLException e) {
            throw new IllegalStateException("The statement failed: " + e.getSQLState(), e);
        }
    }

    /** The first column of every row of a query run as the owner of the database (the audit and mail tables). */
    protected static List<String> owner(String sql, Object... parameters) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                List<String> found = new ArrayList<>();
                while (result.next()) {
                    found.add(result.getString(1));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    /** The audit records of a type about an organization (the audit table is platform-level). */
    protected static long audited(Organization organization, String type) {
        return Long.parseLong(owner("select count(*) from audit_record where event_type = ? and context_tenant_id = ?",
                type, organization.id().value()).get(0));
    }
}

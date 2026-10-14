package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sharing single records and giving them to another owner (ADR-0117): who may, what is refused, what is audited.
 * The record is answered "not found" to a person who cannot reach it, "forbidden" to one who can read it and not
 * change it.
 */
@PlatformIntegrationTest
class RecordSharingIT extends RecordSecuritySupport {

    private static String shares(String record) {
        return ITEM_RECORDS + "/" + record + "/shares";
    }

    private static String shareBody(String type, String grantee, String level) {
        return "{\"granteeType\":\"" + type + "\",\"granteeId\":\"" + grantee + "\",\"accessLevel\":\"" + level + "\"}";
    }

    @Test
    void theOwnerSharesARecordAndSeesWhoItIsSharedWith() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        Person friend = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        Response shared = owner.browser().request("PUT", shares(record), shareBody("USER", friend.userId(), "READ"));
        Response list = owner.browser().get(shares(record));

        assertThat(shared.status()).as(shared.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(shared.body(), "$.data.accessLevel")).isEqualTo("READ");
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[*].granteeId")).containsExactly(friend.userId());
    }

    @Test
    void sharingAgainChangesTheLevelAndNeverAddsASecondShare() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        Person friend = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        owner.browser().request("PUT", shares(record), shareBody("USER", friend.userId(), "READ"));

        Response again = owner.browser().request("PUT", shares(record),
                shareBody("USER", friend.userId(), "READ_WRITE"));

        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(owner.browser().get(shares(record)).body(), "$.data[*].accessLevel"))
                .containsExactly("READ_WRITE");
    }

    @Test
    void aRecordIsNotSharedWithItsOwnerWithSomeoneWhoIsNotAMemberOrWithAGroupThatDoesNotExist() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        Response self = owner.browser().request("PUT", shares(record), shareBody("USER", owner.userId(), "READ"));
        Response stranger = owner.browser().request("PUT", shares(record), shareBody("USER", missing(), "READ"));
        Response group = owner.browser().request("PUT", shares(record), shareBody("GROUP", missing(), "READ"));
        Response level = owner.browser().request("PUT", shares(record), shareBody("USER", missing(), "DELETE"));

        assertThat(self.status()).isEqualTo(400);
        assertThat(stranger.status()).isEqualTo(400);
        assertThat(group.status()).isEqualTo(400);
        assertThat(level.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(owner.browser().get(shares(record)).body(), "$.data")).isEmpty();
    }

    @Test
    void aPersonWhoCanReadARecordButNotChangeItMayNotShareIt() {
        Items items = items();
        share(items.admin(), "PUBLIC_READ", true);
        Person owner = worker(items.organization(), items.admin());
        Person reader = worker(items.organization(), items.admin());
        Person friend = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        Response refused = reader.browser().request("PUT", shares(record),
                shareBody("USER", friend.userId(), "READ"));
        Response listed = reader.browser().get(shares(record));
        Response owned = reader.browser().postJson(ITEM_RECORDS + "/" + record + "/owner",
                "{\"ownerId\":\"" + reader.userId() + "\",\"version\":0}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(listed.status()).isEqualTo(403);
        assertThat(owned.status()).isEqualTo(403);
    }

    @Test
    void aPersonWhoCannotReachARecordIsToldItDoesNotExist() {
        Items items = items();
        share(items.admin(), "PRIVATE", true);
        Person owner = worker(items.organization(), items.admin());
        Person outsider = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        Response refused = outsider.browser().request("PUT", shares(record),
                shareBody("USER", outsider.userId(), "READ"));
        Response nothing = outsider.browser().request("PUT", shares(missing()),
                shareBody("USER", outsider.userId(), "READ"));

        assertThat(refused.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(nothing.body(), "$.error.message"));
        assertThat(listed(outsider.browser())).isEmpty();
    }

    @Test
    void aShareAtTheReadAndWriteLevelLetsTheOtherPersonShareItFurtherButAReadShareDoesNot() {
        Items items = items();
        share(items.admin(), "PRIVATE", true);
        Person owner = worker(items.organization(), items.admin());
        Person editor = worker(items.organization(), items.admin());
        Person reader = worker(items.organization(), items.admin());
        Person third = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        owner.browser().request("PUT", shares(record), shareBody("USER", editor.userId(), "READ_WRITE"));
        owner.browser().request("PUT", shares(record), shareBody("USER", reader.userId(), "READ"));

        Response byEditor = editor.browser().request("PUT", shares(record),
                shareBody("USER", third.userId(), "READ"));
        Response byReader = reader.browser().request("PUT", shares(record),
                shareBody("USER", third.userId(), "READ"));

        assertThat(byEditor.status()).as(byEditor.body()).isEqualTo(200);
        assertThat(byReader.status()).isEqualTo(403);
    }

    @Test
    void takingBackAShareThatDoesNotExistIsNotFound() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        Response gone = owner.browser().request("DELETE", shares(record) + "/" + missing(), null);

        assertThat(gone.status()).isEqualTo(404);
    }

    @Test
    void severalPeopleAndAGroupCanShareTheSameRecord() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        String group = group(items.admin(), "g");
        int accepted = 0;

        for (int index = 0; index < 3; index++) {
            Person friend = worker(items.organization(), items.admin());
            Response shared = owner.browser().request("PUT", shares(record),
                    shareBody("USER", friend.userId(), "READ"));
            accepted += shared.status() == 200 ? 1 : 0;
        }
        Response toGroup = owner.browser().request("PUT", shares(record), shareBody("GROUP", group, "READ"));

        assertThat(accepted).isEqualTo(3);
        assertThat(toGroup.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(owner.browser().get(shares(record)).body(), "$.data")).hasSize(4);
    }

    // ---- the owner ----

    @Test
    void theOwnerGivesARecordToAnotherMemberWhoThenOwnsIt() {
        Items items = items();
        share(items.admin(), "PRIVATE", true);
        Person owner = worker(items.organization(), items.admin());
        Person next = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        Response given = owner.browser().postJson(ITEM_RECORDS + "/" + record + "/owner",
                "{\"ownerId\":\"" + next.userId() + "\",\"version\":0}");

        assertThat(given.status()).as(given.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(given.body(), "$.data.ownerId")).isEqualTo(next.userId());
        assertThat(JsonPath.<Number>read(given.body(), "$.data.version").longValue()).isEqualTo(1L);
        assertThat(listed(next.browser())).containsExactly(record);
        assertThat(listed(owner.browser())).as("the previous owner no longer reaches it").isEmpty();
    }

    @Test
    void theNewOwnerMustBeAMemberAndTheVersionMustBeTheOneRead() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");

        Response stranger = owner.browser().postJson(ITEM_RECORDS + "/" + record + "/owner",
                "{\"ownerId\":\"" + missing() + "\",\"version\":0}");
        Response stale = owner.browser().postJson(ITEM_RECORDS + "/" + record + "/owner",
                "{\"ownerId\":\"" + owner.userId() + "\",\"version\":7}");

        assertThat(stranger.status()).isEqualTo(400);
        assertThat(stale.status()).isEqualTo(409);
    }

    @Test
    void theChangeOfOwnerAndTheSharesAreAuditedByIdentifiersOnly() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        Person next = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        owner.browser().request("PUT", shares(record), shareBody("USER", next.userId(), "READ"));
        owner.browser().postJson(ITEM_RECORDS + "/" + record + "/owner",
                "{\"ownerId\":\"" + next.userId() + "\",\"version\":0}");

        List<String> shared = auditsOf("data.record.shared", record);
        List<String> changed = auditsOf("data.record.owner_changed", record);

        assertThat(shared).hasSize(1);
        assertThat(shared.get(0)).contains("USER").contains("READ").doesNotContain("mine");
        assertThat(changed).hasSize(1);
        assertThat(changed.get(0)).contains(owner.userId()).contains(next.userId());
    }

    @Test
    void aRecordRemovedForGoodTakesItsSharesWithIt() {
        Items items = items();
        Person owner = worker(items.organization(), items.admin());
        Person friend = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        owner.browser().request("PUT", shares(record), shareBody("USER", friend.userId(), "READ"));
        owner.browser().request("DELETE", ITEM_RECORDS + "/" + record, null);

        Response purged = owner.browser().request("DELETE", ITEM_BIN + "/" + record, null);

        assertThat(purged.status()).isEqualTo(204);
        assertThat(owner("select count(*) from record_share where record_id = ?::uuid", record)).containsExactly("0");
    }

    @Test
    void aRecordInTheRecycleBinKeepsItsSharesAndTheyComeBackWithIt() {
        Items items = items();
        share(items.admin(), "PRIVATE", true);
        Person owner = worker(items.organization(), items.admin());
        Person friend = worker(items.organization(), items.admin());
        String record = newItem(owner.browser(), "mine");
        owner.browser().request("PUT", shares(record), shareBody("USER", friend.userId(), "READ"));
        owner.browser().request("DELETE", ITEM_RECORDS + "/" + record, null);
        assertThat(listed(friend.browser())).isEmpty();

        TestBrowser admin = items.admin();
        Response restored = admin.postJson(ITEM_BIN + "/" + record + "/restore", "{}");

        assertThat(restored.status()).as(restored.body()).isEqualTo(200);
        assertThat(listed(friend.browser())).containsExactly(record);
    }
}

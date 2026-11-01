package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestSignIn;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Who may do what through the public API (Sprint 30, ADR-0244, ADR-0245): every operation against every kind of caller
 * the platform knows. The door is new and the house is the same, so each answer is the one the internal interface
 * gives: an object the caller may not read is an object that does not exist, a record they cannot see is a record
 * that does not
 * exist, a field they cannot read is absent and writing it is writing a field that does not exist, and the organization
 * is the host's and nobody else's.
 */
@PlatformIntegrationTest
class PublicApiAccessIT extends PublicApiSupport {

    /** One operation of the public API, as a call. */
    private record Op(String name, Function<Caller, Response> call) {
    }

    /** The operations against one object, one record and one record in the recycle bin. */
    private static List<Op> operations(String object, String id, String binned) {
        String records = OBJECTS + "/" + object + "/records";
        List<Op> all = new ArrayList<>();
        all.add(new Op("describeObject", caller -> caller.get(OBJECTS + "/" + object)));
        all.add(new Op("createRecord", caller -> caller.post(records, createBody("{\"name__c\":\"New\"}"))));
        all.add(new Op("getRecord", caller -> caller.get(records + "/" + id)));
        all.add(new Op("updateRecord", caller -> caller.patch(records + "/" + id,
                "{\"fields\":{\"department__c\":\"X\"}}", "If-Match", "\"0\"")));
        all.add(new Op("deleteRecord", caller -> caller.delete(records + "/" + id)));
        all.add(new Op("restoreRecord", caller -> caller.post(records + "/" + binned + "/restore", "")));
        all.add(new Op("queryRecords", caller -> caller.post(records + "/query", "{}")));
        all.add(new Op("bulkRecords", caller -> caller.post(records + "/bulk",
                "{\"operation\":\"CREATE\",\"records\":[{\"fields\":{\"name__c\":\"Bulk\"}}]}")));
        return all;
    }

    private record World(Organization organization, TestBrowser internalAdmin, Caller admin, String id,
            String binned) {
    }

    private World world() {
        Organization organization = organization();
        TestBrowser internalAdmin = adminWithEmployee(organization);
        Caller admin = caller(organization.host(), internalAdmin);
        String id = newEmployee(admin, "Seen", "Sales");
        String binned = newEmployee(admin, "Binned", "Sales");
        assertThat(admin.delete(EMPLOYEE_RECORDS + "/" + binned).status()).isEqualTo(204);
        return new World(organization, internalAdmin, admin, id, binned);
    }

    // ---- allowed ----

    @Test
    void theAdministratorMayDoEveryOperation() {
        World world = world();
        List<Op> operations = operations("Employee__c", world.id(), world.binned());

        // The order matters: the record is read and changed before it is deleted; the binned one is restored.
        List<String> order = List.of("describeObject", "createRecord", "getRecord", "updateRecord", "queryRecords",
                "bulkRecords", "restoreRecord", "deleteRecord");
        for (String name : order) {
            Response answer = operations.stream().filter(op -> op.name().equals(name)).findFirst().orElseThrow()
                    .call().apply(world.admin());
            assertThat(answer.status()).as(name + ": " + answer.body()).isIn(200, 201, 204);
        }
        assertThat(world.admin().get(OBJECTS).status()).isEqualTo(200);
    }

    // ---- no permission on the object: the object does not exist ----

    @Test
    void aMemberWithNoPermissionOnTheObjectMeetsAnObjectThatDoesNotExist() {
        World world = world();
        Caller nobody = memberWith(world.organization(), world.internalAdmin());

        List<Op> theirs = operations("Employee__c", world.id(), world.binned());
        List<Op> nothing = operations("Nothing__c", world.id(), world.binned());
        for (int index = 0; index < theirs.size(); index++) {
            Response refused = theirs.get(index).call().apply(nobody);
            Response unknown = nothing.get(index).call().apply(world.admin());
            String name = theirs.get(index).name();
            assertThat(refused.status()).as(name).isEqualTo(404);
            assertThat(unknown.status()).as(name + " on an object that does not exist").isEqualTo(404);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).as(name)
                    .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.code"));
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).as(name + " says the same words")
                    .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
        }
        Response listed = nobody.get(OBJECTS);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(listed.body(), "$.data[*].apiName")).doesNotContain("Employee__c");
        assertThat(JsonPath.<List<String>>read(world.admin().get(OBJECTS).body(), "$.data[*].apiName"))
                .contains("Employee__c");
    }

    @Test
    void anObjectThePlatformItselfOwnsIsNotThereEitherForWritingOrReading() {
        World world = world();

        for (String owned : new String[] {"User", "Profile", "AccessPolicy"}) {
            assertThat(world.admin().get(OBJECTS + "/" + owned).status()).as(owned).isEqualTo(404);
            assertThat(world.admin().post(OBJECTS + "/" + owned + "/records", createBody("{}")).status())
                    .as(owned).isEqualTo(404);
            assertThat(world.admin().post(OBJECTS + "/" + owned + "/records/query", "{}").status())
                    .as(owned).isEqualTo(404);
        }
        assertThat(JsonPath.<List<String>>read(world.admin().get(OBJECTS).body(), "$.data[*].apiName"))
                .doesNotContain("User", "Profile", "AccessPolicy");
    }

    // ---- read but not write ----

    @Test
    void aMemberWhoMayOnlyReadIsForbiddenEveryWriteAndMayReadEverything() {
        World world = world();
        Caller reader = memberWith(world.organization(), world.internalAdmin(), "read");

        for (Op op : operations("Employee__c", world.id(), world.binned())) {
            Response answer = op.call().apply(reader);
            switch (op.name()) {
                case "describeObject", "getRecord", "queryRecords" ->
                        assertThat(answer.status()).as(op.name()).isEqualTo(200);
                default -> assertThat(answer.status()).as(op.name() + ": " + answer.body()).isEqualTo(403);
            }
        }
        assertThat(JsonPath.<Boolean>read(reader.get(EMPLOYEES_API).body(), "$.data.canCreate")).isFalse();
        assertThat(world.admin().get(EMPLOYEE_RECORDS + "/" + world.id()).status()).as("nothing changed")
                .isEqualTo(200);
    }

    // ---- no permission on a field ----

    @Test
    void aFieldTheCallerMayNotReadIsAbsentAndWritingItIsWritingAFieldThatDoesNotExist() {
        World world = world();
        String restricted = "[{\"key\":\"Employee__c.name__c\",\"actions\":[\"edit\"]},"
                + "{\"key\":\"Employee__c.department__c\",\"actions\":[\"edit\"]}]";
        Holder holder = holderWith(world.organization(), world.internalAdmin(), "Employee__c", restricted, "read",
                "create", "update");
        Caller limited = caller(world.organization().host(), as(world.organization(), holder.member()));
        String withSalary = newEmployeeWithSalary(world.admin());

        Response read = limited.get(EMPLOYEE_RECORDS + "/" + withSalary);
        assertThat(read.status()).isEqualTo(200);
        assertThat(JsonPath.<java.util.Map<String, Object>>read(read.body(), "$.data.fields")).containsKey("name__c")
                .doesNotContainKey("salary__c");
        assertThat(read.body()).doesNotContain("salary").doesNotContain("null");
        Response described = limited.get(EMPLOYEES_API);
        assertThat(JsonPath.<List<String>>read(described.body(), "$.data.fields[*].apiName"))
                .contains("name__c").doesNotContain("salary__c", "notes__c");

        Response writeHidden = limited.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"X\",\"salary__c\":1}"));
        Response writeUnknown = limited.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"X\",\"nothing__c\":1}"));
        assertThat(writeHidden.status()).isEqualTo(400);
        assertThat(new ArrayList<>(JsonPath.<java.util.Map<String, List<String>>>read(writeHidden.body(),
                "$.error.fields").values())).isEqualTo(new ArrayList<>(JsonPath
                .<java.util.Map<String, List<String>>>read(writeUnknown.body(), "$.error.fields").values()));
        assertThat(writeHidden.body()).as("the hidden field is named only as far as the caller named it")
                .doesNotContain("not allowed to edit");

        Response filtered = limited.post(EMPLOYEE_RECORDS + "/query", "{\"filter\":{\"op\":\"GT\","
                + "\"field\":\"salary__c\",\"values\":[0]}}");
        Response filteredUnknown = limited.post(EMPLOYEE_RECORDS + "/query", "{\"filter\":{\"op\":\"GT\","
                + "\"field\":\"nothing__c\",\"values\":[0]}}");
        assertThat(filtered.status()).isEqualTo(400);
        assertThat(filteredUnknown.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(filtered.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(filteredUnknown.body(), "$.error.message"));
    }

    private String newEmployeeWithSalary(Caller admin) {
        Response created = admin.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Paid\",\"salary__c\":1250.50}"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return id(created);
    }

    // ---- a record the caller cannot see ----

    @Test
    void aRecordTheCallerCannotSeeIsARecordThatDoesNotExistToEveryOperation() {
        World world = world();
        Caller peer = memberWith(world.organization(), world.internalAdmin(), "read", "create", "update", "delete");
        Response current = world.internalAdmin().get("/api/v1/sharing/objects/Employee__c");
        boolean configured = JsonPath.read(current.body(), "$.data.configured");
        String version = configured ? ",\"version\":" + JsonPath.<Number>read(current.body(), "$.data.version") : "";
        Response set = world.internalAdmin().request("PUT", "/api/v1/sharing/objects/Employee__c",
                "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":false" + version + "}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
        String ownIdOfPeer = newEmployee(peer, "Mine", "Ops");

        Response read = peer.get(EMPLOYEE_RECORDS + "/" + world.id());
        Response readMissing = peer.get(EMPLOYEE_RECORDS + "/" + key().substring(4));
        Response change = peer.patch(EMPLOYEE_RECORDS + "/" + world.id(), "{\"fields\":{\"department__c\":\"X\"}}",
                "If-Match", "\"0\"");
        Response delete = peer.delete(EMPLOYEE_RECORDS + "/" + world.id());
        Response queried = peer.post(EMPLOYEE_RECORDS + "/query", "{}");

        assertThat(read.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(read.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(readMissing.body(), "$.error.message"));
        assertThat(change.status()).isEqualTo(404);
        assertThat(delete.status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(queried.body(), "$.data[*].id")).containsExactly(ownIdOfPeer);
        assertThat(world.admin().get(EMPLOYEE_RECORDS + "/" + world.id()).status()).as("untouched").isEqualTo(200);
    }

    // ---- another organization, a forged header ----

    @Test
    void theTokenOfAnotherOrganizationNeverOpensThisOnesRecordsOnAnyOperation() {
        World mine = world();
        World theirs = world();
        Caller intruder = mine.admin().onHost(theirs.organization().host());

        for (Op op : operations("Employee__c", theirs.id(), theirs.binned())) {
            Response answer = op.call().apply(intruder);
            assertThat(answer.status()).as(op.name()).isIn(401, 403, 404);
            assertThat(answer.body()).as(op.name()).doesNotContain("Seen").doesNotContain("Binned");
        }
        assertThat(intruder.get(OBJECTS).status()).isIn(401, 403, 404);
        assertThat(JsonPath.<String>read(theirs.admin().get(EMPLOYEE_RECORDS + "/" + theirs.id()).body(),
                "$.data.fields.department__c")).as("theirs is untouched").isEqualTo("Sales");
    }

    @Test
    void aMemberOfOneOrganizationNeverReachesARecordOfAnotherByItsIdentifier() {
        World mine = world();
        World theirs = world();

        for (Op op : operations("Employee__c", theirs.id(), theirs.binned())) {
            Response answer = op.call().apply(mine.admin());
            if (!"createRecord".equals(op.name()) && !"queryRecords".equals(op.name())
                    && !"bulkRecords".equals(op.name()) && !"describeObject".equals(op.name())) {
                assertThat(answer.status()).as(op.name()).isEqualTo(404);
            }
        }
        assertThat(JsonPath.<List<String>>read(theirs.admin().post(EMPLOYEE_RECORDS + "/query", "{}").body(),
                "$.data[*].id")).contains(theirs.id()).doesNotContain(key());
    }

    @Test
    void aForgedTenantHeaderChangesNothingOnAnyOperation() {
        World mine = world();
        World theirs = world();
        String[] forged = {"X-Tenant-Id", theirs.organization().id().value().toString(), "X-Tenant",
            theirs.organization().tenant().slug(), "X-Forwarded-Host", theirs.organization().host(),
            "X-Organization", theirs.organization().tenant().slug()};

        Caller forger = mine.admin().withHeaders(forged);
        String secret = theirs.id();

        // Every operation answers as it does without the headers: the host is the only thing that names the
        // organization.
        for (Op op : operations("Employee__c", mine.id(), mine.binned())) {
            Response answer = op.call().apply(forger);
            assertThat(answer.status()).as(op.name() + ": " + answer.body()).isIn(200, 201, 204);
        }
        assertThat(forger.get(OBJECTS).status()).isEqualTo(200);
        assertThat(forger.get(EMPLOYEE_RECORDS + "/" + secret).status())
                .as("their record by its identifier, with their host in my header").isEqualTo(404);
        Response listed = forger.post(EMPLOYEE_RECORDS + "/query", "{}");
        assertThat(JsonPath.<List<String>>read(listed.body(), "$.data[*].id")).doesNotContain(secret);
        assertThat(JsonPath.<List<String>>read(theirs.admin().post(EMPLOYEE_RECORDS + "/query", "{}").body(),
                "$.data[*].id")).as("nothing was made in theirs").containsOnly(theirs.id());
    }

    // ---- the platform host, a platform person, not signed in, revoked ----

    @Test
    void theAddressesOfThePublicApiAreNotThereOnThePlatformHostForAnyone() {
        World world = world();
        // A person who signed in on the platform host: they have a valid token there and no organization.
        var person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        Caller onPlatformHost = caller(TestSignIn.PLATFORM_HOST, TestPlatform.signedIn(port, person));
        Caller nobodyKnown = anonymous("no-such-organization.platform.example.test");
        Response unknownOrganization = nobodyKnown.get(OBJECTS);

        for (Op op : operations("Employee__c", world.id(), world.binned())) {
            Response answer = op.call().apply(onPlatformHost);
            assertThat(answer.status()).as(op.name()).isEqualTo(404);
            assertThat(JsonPath.<String>read(answer.body(), "$.error.code")).isEqualTo("NOT_FOUND");
        }
        assertThat(world.admin().onHost(TestSignIn.PLATFORM_HOST).get(OBJECTS).status())
                .as("a token of an organization is not a token of the platform host").isIn(401, 404);
        Response listed = onPlatformHost.get(OBJECTS);
        assertThat(listed.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(listed.body(), "$.error.message"))
                .as("the words of an organization that is not there")
                .isEqualTo(JsonPath.<String>read(unknownOrganization.body(), "$.error.message"));
    }

    @Test
    void aPlatformPersonWithoutMembershipIsRefusedOnAnOrganizationHost() {
        World world = world();
        var person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformBrowser = TestPlatform.signedIn(port, person);
        Caller platformPerson = caller(world.organization().host(), platformBrowser);

        for (Op op : operations("Employee__c", world.id(), world.binned())) {
            Response answer = op.call().apply(platformPerson);
            assertThat(answer.status()).as(op.name()).isIn(401, 403, 404);
            assertThat(answer.body()).doesNotContain("Seen");
        }
        assertThat(platformPerson.get(OBJECTS).status()).isIn(401, 403, 404);
    }

    @Test
    void withoutATokenOrWithOneThatIsNotValidEveryOperationIsRefusedAs401() {
        World world = world();
        Caller nobody = world.admin().withToken(null);
        Caller garbage = world.admin().withToken("not-a-token-at-all");

        for (Op op : operations("Employee__c", world.id(), world.binned())) {
            assertThat(op.call().apply(nobody).status()).as(op.name() + " without a token").isEqualTo(401);
            assertThat(op.call().apply(garbage).status()).as(op.name() + " with a bad token").isEqualTo(401);
        }
        assertThat(nobody.get(OBJECTS).status()).isEqualTo(401);
        assertThat(nobody.get(OBJECTS).header("WWW-Authenticate")).isPresent();
    }

    @Test
    void aTokenThatWasRevokedByASignOutStopsWorkingAtOnce() {
        World world = world();
        Caller person = world.admin();
        assertThat(person.get(OBJECTS).status()).isEqualTo(200);

        assertThat(world.internalAdmin().postJson("/api/v1/auth/sign-out", "{}").status()).isIn(200, 204);

        assertThat(person.get(OBJECTS).status()).isEqualTo(401);
        assertThat(person.post(EMPLOYEE_RECORDS + "/query", "{}").status()).isEqualTo(401);
    }

    @Test
    void theSessionCookieIsNeverTurnedIntoATokenForThePublicApi() {
        World world = world();

        // The browser of the same person, with its cookies, calls the public address as a page would: no bearer
        // header, so no sign-in. (A browser does not even send the cookie to this prefix: its path is the
        // internal one.)
        Response viaCookie = world.internalAdmin().get(OBJECTS);

        assertThat(viaCookie.status()).isEqualTo(401);
        assertThat(world.internalAdmin().postJson(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"Forged\"}")).status())
                .as("and without the forgery check a cookie would need, a write is a plain 401").isEqualTo(401);
    }

    @Test
    void theTokenOfACustomPagesScriptNeverReachesThePublicApi() {
        World world = world();
        Response issued = world.internalAdmin().postJson("/api/v1/embedded-tokens",
                "{\"customPage\":\"Inspection__c\"}");
        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        String token = JsonPath.read(issued.body(), "$.data.token");

        for (Op op : operations("Employee__c", world.id(), world.binned())) {
            assertThat(op.call().apply(world.admin().withToken(token)).status()).as(op.name()).isEqualTo(403);
        }
        assertThat(world.admin().withToken(token).get(OBJECTS).status()).isEqualTo(403);
    }

    @Test
    void thereIsNoCrossOriginRuleForThePublicApi() {
        World world = world();

        Response preflight = world.admin().request("OPTIONS", EMPLOYEE_RECORDS + "/query", null, "Origin", "null",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization");
        Response answered = world.admin().get(OBJECTS, "Origin", "null");
        Response fromAnotherSite = world.admin().get(OBJECTS, "Origin", "https://app.example.test");

        assertThat(preflight.header("Access-Control-Allow-Origin")).isEmpty();
        assertThat(answered.header("Access-Control-Allow-Origin")).isEmpty();
        assertThat(fromAnotherSite.header("Access-Control-Allow-Origin")).isEmpty();
        assertThat(answered.header("Access-Control-Allow-Credentials")).isEmpty();
    }
}

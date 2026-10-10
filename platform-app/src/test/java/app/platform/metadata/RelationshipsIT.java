package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Relationships (Sprint 11, ADR-0063) through the real application: both directions of a lookup and a master-detail,
 * one-to-one, many-to-many through a junction object, and the rules of what happens when the parent goes.
 */
@PlatformIntegrationTest
class RelationshipsIT extends LifecycleSupport {

    private static String relationships(TestBrowser admin, String object) {
        Response response = admin.get(OBJECTS + "/" + object + "/relationships");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    @Test
    void aLookupIsAParentOfTheChildAndAChildListOfTheParent() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Department");
        createObject(admin, "Employee");
        Response added = createField(admin, "Employee__c", reference("department", "LOOKUP", "Department__c",
                ",\"listLabel\":\"Staff\""));
        assertThat(added.status()).as(added.body()).isEqualTo(201);

        String child = relationships(admin, "Employee__c");
        String parent = relationships(admin, "Department__c");

        assertThat(JsonPath.<List<String>>read(child, "$.data.parents[*].type")).containsExactly("MANY_TO_ONE");
        assertThat(JsonPath.<List<String>>read(child, "$.data.parents[*].parentObject"))
                .containsExactly("Department__c");
        assertThat(JsonPath.<List<String>>read(parent, "$.data.children[*].type")).containsExactly("ONE_TO_MANY");
        assertThat(JsonPath.<List<String>>read(parent, "$.data.children[*].childObject"))
                .containsExactly("Employee__c");
        assertThat(JsonPath.<List<String>>read(parent, "$.data.children[*].field")).containsExactly("department__c");
        assertThat(JsonPath.<List<String>>read(parent, "$.data.children[*].listLabel")).containsExactly("Staff");
        assertThat(JsonPath.<List<String>>read(parent, "$.data.children[*].onDelete")).containsExactly("CLEAR");
    }

    @Test
    void theListOnTheParentIsNamedAfterTheChildWhenNoLabelIsGiven() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Department");
        createObject(admin, "Employee");
        createField(admin, "Employee__c", reference("department", "LOOKUP", "Department__c", ""));

        String parent = relationships(admin, "Department__c");

        assertThat(JsonPath.<List<String>>read(parent, "$.data.children[*].listLabel")).containsExactly("Employees");
    }

    @Test
    void aUniqueLookupIsOneToOneInBothDirections() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Person");
        createObject(admin, "Passport");
        Response added = createField(admin, "Passport__c", "{\"name\":\"holder\",\"label\":\"Holder\","
                + "\"type\":\"LOOKUP\",\"unique\":true,\"settings\":{\"targetObject\":\"Person__c\"}}");
        assertThat(added.status()).as(added.body()).isEqualTo(201);

        assertThat(JsonPath.<List<String>>read(relationships(admin, "Passport__c"), "$.data.parents[*].type"))
                .containsExactly("ONE_TO_ONE");
        assertThat(JsonPath.<List<String>>read(relationships(admin, "Person__c"), "$.data.children[*].type"))
                .containsExactly("ONE_TO_ONE");
    }

    @Test
    void aJunctionObjectWithTwoMastersRelatesThemManyToMany() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Student");
        createObject(admin, "Course");
        createObject(admin, "Enrolment");
        createField(admin, "Enrolment__c", reference("student", "MASTER_DETAIL", "Student__c", ""));
        createField(admin, "Enrolment__c", reference("course", "MASTER_DETAIL", "Course__c", ""));

        String students = relationships(admin, "Student__c");
        String courses = relationships(admin, "Course__c");

        assertThat(JsonPath.<List<String>>read(students, "$.data.manyToMany[*].type")).containsExactly("MANY_TO_MANY");
        assertThat(JsonPath.<List<String>>read(students, "$.data.manyToMany[*].otherObject"))
                .containsExactly("Course__c");
        assertThat(JsonPath.<List<String>>read(students, "$.data.manyToMany[*].viaObject"))
                .containsExactly("Enrolment__c");
        assertThat(JsonPath.<List<String>>read(courses, "$.data.manyToMany[*].otherObject"))
                .containsExactly("Student__c");
        assertThat(JsonPath.<List<String>>read(students, "$.data.children[*].onDelete")).containsExactly("CASCADE");
    }

    @Test
    void theSystemLookupsEveryObjectHasAreNotRelationships() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Thing");

        String thing = relationships(admin, "Thing__c");
        String user = relationships(admin, "User");

        assertThat(JsonPath.<List<Object>>read(thing, "$.data.parents")).isEmpty();
        assertThat(JsonPath.<List<String>>read(user, "$.data.children[*].field"))
                .as("no owner, created-by or changed-by links")
                .doesNotContain("ownerId", "createdById", "updatedById");
    }

    @Test
    void aStandardRelationshipIsShownToo() {
        TestBrowser admin = adminOf(organization());

        String account = relationships(admin, "Account");

        assertThat(JsonPath.<List<String>>read(account, "$.data.children[*].childObject")).contains("Contact",
                "Opportunity", "Case");
        assertThat(JsonPath.<List<String>>read(account, "$.data.parents[*].field")).contains("parentAccountId");
    }

    @Test
    void aMasterDetailRemovesItsDetailsAndCannotBeToldOtherwise() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Invoice");
        createObject(admin, "Line");

        Response cascade = createField(admin, "Line__c", reference("invoice", "MASTER_DETAIL", "Invoice__c",
                ",\"reparentable\":true,\"listLabel\":\"Lines\""));
        Response told = createField(admin, "Line__c", reference("other", "MASTER_DETAIL", "Invoice__c",
                ",\"onDelete\":\"CLEAR\""));

        assertThat(cascade.status()).as(cascade.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(cascade.body(), "$.data.settings.reparentable")).isTrue();
        assertThat(cascade.body()).as("a master-detail shows no delete choice").doesNotContain("onDelete");
        assertThat(told.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(told.body(), "$.error.fields['settings.onDelete']"))
                .containsExactly("This setting is not used by a field of this type.");
    }

    @Test
    void aLookupClearsOrRefusesAndNothingElse() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createObject(admin, "Member");

        Response refuse = createField(admin, "Member__c", reference("team", "LOOKUP", "Team__c",
                ",\"onDelete\":\"REFUSE\""));
        Response cascade = createField(admin, "Member__c", reference("team2", "LOOKUP", "Team__c",
                ",\"onDelete\":\"CASCADE\""));
        Response reparent = createField(admin, "Member__c", reference("team3", "LOOKUP", "Team__c",
                ",\"reparentable\":true"));

        assertThat(refuse.status()).as(refuse.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(refuse.body(), "$.data.settings.onDelete")).isEqualTo("REFUSE");
        assertThat(cascade.status()).as("a lookup never removes the records that point at the parent").isEqualTo(400);
        assertThat(reparent.status()).as("re-parenting is a master-detail setting").isEqualTo(400);
    }

    @Test
    void aRequiredLookupRefusesByDefaultAndCannotClear() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createObject(admin, "Member");

        Response byDefault = createField(admin, "Member__c", "{\"name\":\"team\",\"label\":\"Team\","
                + "\"type\":\"LOOKUP\",\"required\":true,\"settings\":{\"targetObject\":\"Team__c\"}}");
        Response clearing = createField(admin, "Member__c", "{\"name\":\"team2\",\"label\":\"Team\","
                + "\"type\":\"LOOKUP\",\"required\":true,\"settings\":{\"targetObject\":\"Team__c\","
                + "\"onDelete\":\"CLEAR\"}}");

        assertThat(byDefault.status()).as(byDefault.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(byDefault.body(), "$.data.settings.onDelete")).isEqualTo("REFUSE");
        assertThat(clearing.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(clearing.body(), "$.error.fields['settings.onDelete']"))
                .containsExactly("A required lookup cannot empty its link: choose REFUSE.");
    }

    @Test
    void theBehaviourOfAnExistingLookupCanBeChangedButNotItsTarget() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createObject(admin, "Other");
        createObject(admin, "Member");
        createField(admin, "Member__c", reference("team", "LOOKUP", "Team__c", ""));

        Response changed = admin.request("PUT", OBJECTS + "/Member__c/fields/team__c", "{\"label\":\"Team\","
                + "\"description\":\"\",\"required\":false,\"unique\":false,\"settings\":{\"targetObject\":"
                + "\"Team__c\",\"onDelete\":\"REFUSE\",\"listLabel\":\"People\"},\"version\":0}");
        Response retargeted = admin.request("PUT", OBJECTS + "/Member__c/fields/team__c", "{\"label\":\"Team\","
                + "\"description\":\"\",\"required\":false,\"unique\":false,\"settings\":{\"targetObject\":"
                + "\"Other__c\"},\"version\":1}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(changed.body(), "$.data.settings.onDelete")).isEqualTo("REFUSE");
        assertThat(JsonPath.<String>read(changed.body(), "$.data.settings.listLabel")).isEqualTo("People");
        assertThat(retargeted.status()).isEqualTo(400);
    }

    @Test
    void anObjectOtherFieldsPointToStaysUntilTheyGo() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Team");
        createObject(admin, "Member");
        createField(admin, "Member__c", reference("team", "LOOKUP", "Team__c", ""));

        Response refused = admin.request("DELETE", OBJECTS + "/Team__c", null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("Member__c.team__c");
    }
}

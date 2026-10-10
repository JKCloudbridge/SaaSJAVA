package app.platform.testsupport;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes custom objects and fields exist in an organization for tests, as rows written with the tenant set, the way the
 * application would have written them but without going through the API (the tests of the metadata API do that
 * themselves). Every test organization made by {@link TestOrganizations} gets the two sample objects, so the tests of
 * permissions on data, which need something to name, keep working now that the stand-in catalogue of Sprint 8 is gone
 * (ADR-0058).
 */
public final class TestObjects {

    /** The first sample object: three text fields. */
    public static final String OBJECT_A = "ObjectA__c";

    /** The second sample object: one text field. */
    public static final String OBJECT_B = "ObjectB__c";

    /** The fields of the sample objects. */
    public static final String FIELD_A = "fieldA__c";

    /** The second field of the first sample object. */
    public static final String FIELD_B = "fieldB__c";

    /** The third field of the first sample object. */
    public static final String FIELD_C = "fieldC__c";

    private TestObjects() {
    }

    /** Gives the organization {@link #OBJECT_A} (three fields) and {@link #OBJECT_B} (one field). */
    public static void addSamples(TenantId tenant) {
        Map<String, String> three = new LinkedHashMap<>();
        three.put(FIELD_A, "Field A");
        three.put(FIELD_B, "Field B");
        three.put(FIELD_C, "Field C");
        addObject(tenant, OBJECT_A, "Object A", three);
        addObject(tenant, OBJECT_B, "Object B", Map.of(FIELD_A, "Field A"));
    }

    /** Creates a custom object with text fields (API name to label), in the order of the map. */
    public static void addObject(TenantId tenant, String apiName, String label, Map<String, String> textFields) {
        try {
            TenantFixtures.asTenant(tenant, connection -> {
                TenantFixtures.update(connection,
                        "insert into object_definition (tenant_id, api_name, label, plural_label, created_by, "
                                + "updated_by) values (?, ?, ?, ?, ?, ?)",
                        tenant.value(), apiName, label, label + "s", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                int order = 10;
                for (Map.Entry<String, String> field : textFields.entrySet()) {
                    TenantFixtures.update(connection,
                            "insert into field_definition (tenant_id, object_api_name, api_name, label, data_type, "
                                    + "config, sort_order, created_by, updated_by) "
                                    + "values (?, ?, ?, ?, 'TEXT', cast('{\"maxLength\":255}' as jsonb), ?, ?, ?)",
                            tenant.value(), apiName, field.getKey(), field.getValue(), order,
                            ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
                    order += 10;
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not add a test object", e);
        }
    }
}

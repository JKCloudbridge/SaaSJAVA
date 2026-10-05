package app.platform.metadata.internal;

import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldType;
import app.platform.metadata.PicklistValue;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Developer convenience: gives the two local organizations one object of their own each, so that the object manager and
 * the permission editors have something to show on a developer machine: {@code Employee__c} in {@code tenant-a} and
 * {@code Vehicle__c} in {@code tenant-b} (the exit criterion of Sprint 10, ready to look at). Only the {@code local}
 * profile runs it, and only when the organization does not have the object yet. It replaces the sample objects of the
 * settings that Sprint 8 used before the metadata module existed.
 */
@Component
@Profile("local")
@Order(3)
class LocalMetadataSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalMetadataSeeder.class);

    private record Field(String name, String label, FieldType type, boolean required, boolean unique,
            FieldConfiguration configuration) {
    }

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final MetadataStore store;
    private final ConfigCodec codec;

    LocalMetadataSeeder(Tenants tenants, TenantContexts contexts, TransactionTemplate transaction,
            MetadataStore store, ConfigCodec codec) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.transaction = transaction;
        this.store = store;
        this.codec = codec;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("tenant-a", "Employee__c", "Employee", "Employees", List.of(
                new Field("employeeNumber__c", "Employee number", FieldType.TEXT, true, true,
                        new TextConfiguration(20)),
                new Field("department__c", "Department", FieldType.PICKLIST, false, false,
                        new PicklistConfiguration(List.of(new PicklistValue("Engineering", "Engineering", true),
                                new PicklistValue("Finance", "Finance", true),
                                new PicklistValue("Operations", "Operations", true),
                                new PicklistValue("Sales", "Sales", true)))),
                new Field("joiningDate__c", "Joining date", FieldType.DATE, false, false,
                        new FieldConfiguration.NoConfiguration()),
                new Field("salary__c", "Salary", FieldType.CURRENCY, false, false,
                        new DecimalConfiguration(18, 2))));
        seed("tenant-b", "Vehicle__c", "Vehicle", "Vehicles", List.of(
                new Field("plateNumber__c", "Plate number", FieldType.TEXT, true, true, new TextConfiguration(20)),
                new Field("model__c", "Model", FieldType.TEXT, false, false, new TextConfiguration(80)),
                new Field("serviceDate__c", "Next service", FieldType.DATE, false, false,
                        new FieldConfiguration.NoConfiguration())));
    }

    private void seed(String slug, String apiName, String label, String plural, List<Field> fields) {
        tenants.findBySlug(new TenantSlug(slug)).map(Tenant::id).ifPresent(tenant ->
                contexts.run(TenantContext.of(tenant), () -> transaction.executeWithoutResult(status -> {
                    if (store.object(apiName).isPresent()) {
                        return;
                    }
                    var actor = ActorId.SYSTEM.value();
                    store.insertObject(apiName, label, plural, "Sample object for the local profile.", actor);
                    int order = 10;
                    for (Field field : fields) {
                        store.insertField(apiName, field.name(), field.label(), "", field.type().name(),
                                field.required(), field.unique(), null, codec.write(field.configuration()), order,
                                actor);
                        order += 10;
                    }
                    LOG.info("Local organization {} has its sample object {}", slug, apiName);
                })));
    }
}

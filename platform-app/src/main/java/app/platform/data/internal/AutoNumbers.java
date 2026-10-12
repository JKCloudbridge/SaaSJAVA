package app.platform.data.internal;

import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.tenant.TenantContexts;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Gives a new record the next number of an auto-number field (ADR-0096). The count is one row per organization, object
 * and field ({@code auto_number_counter}); taking a number is one statement that creates the row at the field's first
 * number or moves it on, so two instances creating records at once get two different numbers and a number is never
 * given twice, not even after its record is deleted. The row stays locked until the create commits: the pipeline takes
 * its numbers last, just before the record is stored, to keep that time short. A create that is rolled back leaves a
 * gap, on purpose: a count without gaps would make every create of the object wait for the one before it.
 *
 * <p>A number longer than the field's width is not cut: the width is the least, not the most, characters of the digits.
 * Changing the settings of a field later (another prefix, another start) does not move the count back: the digits go
 * on from the counter, the prefix is the current one.
 */
@Component
class AutoNumbers {

    // The first create of a field makes the row (the first number is given and the row holds the one after it); every
    // later create moves it on by one. Names and numbers are parameters; the statement is a constant.
    private static final String NEXT = "insert into auto_number_counter "
            + "(tenant_id, object_api_name, field_api_name, next_value, created_by, updated_by) "
            + "values (:tenant, :object, :field, :after, :actor, :actor) "
            + "on conflict (tenant_id, object_api_name, field_api_name) where deleted_at is null "
            + "do update set next_value = auto_number_counter.next_value + 1, "
            + "version = auto_number_counter.version + 1, updated_by = :actor "
            + "returning next_value - 1";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    AutoNumbers(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** The next number of the field, written as its settings say: the prefix, then the digits padded with zeros. */
    String next(String objectApiName, String fieldApiName, AutoNumberConfiguration configuration, UUID actor) {
        long number = jdbc.sql(NEXT).param("tenant", contexts.require().tenantId().value())
                .param("object", objectApiName).param("field", fieldApiName)
                .param("after", configuration.startAt() + 1).param("actor", actor).query(Long.class).single();
        return format(configuration, number);
    }

    /** A number written as the settings say (also the check of the format in the tests). */
    static String format(AutoNumberConfiguration configuration, long number) {
        String digits = Long.toString(number);
        return configuration.prefix() + "0".repeat(Math.max(0, configuration.width() - digits.length())) + digits;
    }
}

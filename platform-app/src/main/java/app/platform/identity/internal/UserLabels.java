package app.platform.identity.internal;

import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Names the {@code User} object for a screen (Sprint 19, ADR-0129): the display name of a person with an
 * <strong>active</strong> membership of the current organization, which is exactly who {@link UserDirectory} says a
 * link may point to. A person who is not a member, a member who has been deactivated and an identifier that names
 * nobody get no name, so the answer does not tell them apart. Nothing else is read: no e-mail address, no status.
 *
 * <p>The search is for a picker that fills a link: the text must match part of a name (case does not matter), a bounded
 * number of names come back, and the order is by name. The characters that mean something to the pattern are escaped,
 * so a typed {@code %} is a percent sign.
 */
@Component
class UserLabels implements ReferenceLabels {

    private static final String BASE = "select u.id, u.display_name from membership m join platform_user u "
            + "on u.id = m.user_id where m.tenant_id = :tenant and m.status = 'ACTIVE' and m.deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    UserLabels(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    @Override
    public String object() {
        return "User";
    }

    @Override
    public Map<UUID, String> labels(Collection<UUID> identifiers) {
        if (identifiers.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(BASE + " and u.id in (:ids)").param("tenant", contexts.require().tenantId().value())
                .param("ids", List.copyOf(identifiers)).query(UserLabels::option).list().stream()
                .collect(Collectors.toUnmodifiableMap(Option::id, Option::label, (first, second) -> first));
    }

    @Override
    public List<Option> options(String text, int limit) {
        String pattern = "%" + escape(text == null ? "" : text.strip()) + "%";
        return jdbc.sql(BASE + " and u.display_name ilike :pattern escape '\\' order by u.display_name, u.id "
                        + "limit :limit")
                .param("tenant", contexts.require().tenantId().value()).param("pattern", pattern)
                .param("limit", limit).query(UserLabels::option).list();
    }

    private static Option option(ResultSet rs, int row) throws SQLException {
        return new Option(rs.getObject("id", UUID.class), rs.getString("display_name"));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

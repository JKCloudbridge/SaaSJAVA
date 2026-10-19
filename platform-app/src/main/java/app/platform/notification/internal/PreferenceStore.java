package app.platform.notification.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The statements about {@code notification_preference} (ADR-0156). A row is a person's explicit choice; no row means
 * "on". Like the rest of the module it runs under the tenant context that was open when the transaction began.
 */
@Repository
class PreferenceStore {

    private final JdbcClient jdbc;

    PreferenceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The person's explicit choices, by {@code category/channel}. */
    Map<String, Boolean> choicesOf(UUID user) {
        Map<String, Boolean> choices = new HashMap<>();
        jdbc.sql("select category, channel, enabled from notification_preference where user_id = :user "
                        + "and deleted_at is null")
                .param("user", user).query((rs, row) -> {
                    choices.put(rs.getString("category") + "/" + rs.getString("channel"), rs.getBoolean("enabled"));
                    return 0;
                }).list();
        return choices;
    }

    /** The person's explicit choice for one switch, or null when they never touched it. */
    Boolean choice(UUID user, String category, String channel) {
        return jdbc.sql("select enabled from notification_preference where user_id = :user and category = :category "
                        + "and channel = :channel and deleted_at is null")
                .param("user", user).param("category", category).param("channel", channel)
                .query(Boolean.class).optional().orElse(null);
    }

    /** Sets the switch, making the row when the person never touched it. */
    void set(UUID user, String category, String channel, boolean enabled) {
        int changed = jdbc.sql("update notification_preference set enabled = :enabled, version = version + 1, "
                        + "updated_by = :user where user_id = :user and category = :category and channel = :channel "
                        + "and deleted_at is null")
                .param("enabled", enabled).param("user", user).param("category", category)
                .param("channel", channel).update();
        if (changed == 0) {
            jdbc.sql("insert into notification_preference (user_id, category, channel, enabled, created_by, "
                            + "updated_by) values (:user, :category, :channel, :enabled, :user, :user) "
                            + "on conflict (tenant_id, user_id, category, channel) where deleted_at is null "
                            + "do update set enabled = excluded.enabled, version = notification_preference.version + 1")
                    .param("user", user).param("category", category).param("channel", channel)
                    .param("enabled", enabled).update();
        }
    }
}

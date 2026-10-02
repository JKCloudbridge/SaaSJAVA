package app.platform.notification.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mail_queue table (ADR-0024). Platform-level: no tenant context is needed or used.
 *
 * <p><strong>Claiming</strong> works like the outbox's (ADR-0016): one statement picks due rows with
 * {@code FOR UPDATE SKIP LOCKED}, gives each a lease, counts the attempt, and bumps the version; the version after the
 * claim is the claim's token, so an instance that was too slow and lost its lease cannot overwrite the outcome of the
 * one that took over.
 */
@Repository
class MailStore {

    /** A mail this instance has claimed. */
    record ClaimedMail(UUID id, MailTemplate template, String email, UUID userId, Map<String, String> variables,
            Instant createdAt, int attempt, long version) {

        @Override
        public String toString() {
            // The address is personal data: it stays out of any log line.
            return "ClaimedMail[" + id + ", " + template + ", attempt " + attempt + "]";
        }
    }

    private static final int PURGE_BATCH = 1000;
    private static final TypeReference<Map<String, String>> VARIABLES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    MailStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(MailRequest request) {
        jdbc.sql("insert into mail_queue (template, email, user_id, variables, created_by, updated_by) "
                        + "values (:template, :email, :user, cast(:variables as jsonb), :actor, :actor)")
                .param("template", request.template().name())
                .param("email", request.email())
                .param("user", request.userId())
                .param("variables", json.writeValueAsString(request.variables()))
                .param("actor", ActorId.SYSTEM.value())
                .update();
    }

    boolean queuedRecently(MailTemplate template, UUID userId, long withinSeconds) {
        return jdbc.sql("select exists (select 1 from mail_queue where user_id = :user and template = :template "
                        + "and created_at > now() - make_interval(secs => cast(:seconds as double precision)))")
                .param("user", userId)
                .param("template", template.name())
                .param("seconds", (double) withinSeconds)
                .query(Boolean.class)
                .single();
    }

    /** Claims up to {@code limit} due mails, oldest first. */
    List<ClaimedMail> claim(int limit, Duration lease) {
        return jdbc.sql("""
                update mail_queue m
                   set locked_until = now() + make_interval(secs => cast(:lease as double precision)),
                       attempts = m.attempts + 1, version = m.version + 1, updated_by = :system
                 where m.id in (select d.id from mail_queue d
                                 where d.status = 'QUEUED' and d.next_attempt_at <= now()
                                   and (d.locked_until is null or d.locked_until < now())
                                 order by d.next_attempt_at, d.id
                                 limit :limit
                                 for update skip locked)
                returning m.id, m.template, m.email, m.user_id, m.variables::text as variables, m.created_at,
                          m.attempts, m.version
                """)
                .param("lease", lease.toMillis() / 1000.0)
                .param("system", ActorId.SYSTEM.value())
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /** @return false when the claim was lost */
    boolean markSent(ClaimedMail mail, String outcome) {
        return finish(mail, "status = 'SENT', sent_at = now(), last_error_type = null, outcome = :outcome",
                Map.of("outcome", outcome));
    }

    /** Nothing is to be sent for this one. @return false when the claim was lost */
    boolean markSuppressed(ClaimedMail mail, String outcome) {
        return finish(mail, "status = 'SUPPRESSED', last_error_type = null, outcome = :outcome",
                Map.of("outcome", outcome));
    }

    /** Puts the mail back, due again after {@code delay}. @return false when the claim was lost */
    boolean scheduleRetry(ClaimedMail mail, Duration delay, String errorType) {
        return finish(mail, "next_attempt_at = now() + make_interval(secs => cast(:delay as double precision)), "
                + "last_error_type = :error", Map.of("delay", delay.toMillis() / 1000.0, "error", errorType));
    }

    /** Sets the mail aside as a dead letter. @return false when the claim was lost */
    boolean markDead(ClaimedMail mail, String errorType) {
        return finish(mail, "status = 'DEAD', last_error_type = :error, outcome = 'dead'",
                Map.of("error", errorType));
    }

    /** Removes finished mails (sent or suppressed) older than the retention, at most one batch. */
    int purgeFinished(Duration retention) {
        return purge("status in ('SENT', 'SUPPRESSED')", retention);
    }

    /** Removes dead letters older than the retention, at most one batch. */
    int purgeDead(Duration retention) {
        return purge("status = 'DEAD'", retention);
    }

    private int purge(String condition, Duration retention) {
        return jdbc.sql("delete from mail_queue where id in (select id from mail_queue where " + condition
                        + " and updated_at < now() - make_interval(secs => cast(:seconds as double precision)) "
                        + "limit " + PURGE_BATCH + ")")
                .param("seconds", retention.toMillis() / 1000.0)
                .update();
    }

    private boolean finish(ClaimedMail mail, String assignments, Map<String, Object> arguments) {
        var statement = jdbc.sql("update mail_queue set " + assignments + ", locked_until = null, "
                        + "updated_by = :system, version = version + 1 where id = :id and version = :version")
                .param("system", ActorId.SYSTEM.value())
                .param("id", mail.id())
                .param("version", mail.version());
        for (var argument : arguments.entrySet()) {
            statement = statement.param(argument.getKey(), argument.getValue());
        }
        return statement.update() == 1;
    }

    private ClaimedMail map(ResultSet rs, int row) throws SQLException {
        Map<String, String> variables = json.readValue(rs.getString("variables"), VARIABLES);
        return new ClaimedMail(
                rs.getObject("id", UUID.class),
                MailTemplate.valueOf(rs.getString("template")),
                rs.getString("email"),
                rs.getObject("user_id", UUID.class),
                variables,
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getInt("attempts"),
                rs.getLong("version"));
    }
}

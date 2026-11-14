package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The memory of publishing a document to its plants and of the reviewers chosen for each (MC2A, ADR-0260): the rows of
 * {@code inspection_plant_publication} and {@code inspection_plant_reviewer}, and no other class names them (a test
 * scans the sources). Every statement is a constant with parameters; the organization is a parameter taken from the
 * tenant context in addition to the forced row level security. What may be published, to whom and by whom is decided by
 * {@link PlantPublishing}; the database refuses a delete, a change of what a row says and a change of a revoked row
 * whatever is asked (V093).
 *
 * <p>Plant codes, groups and reviewers are the organization's: a row never reaches a log, an audit record or a
 * metric, and {@code toString} of every record prints its identity only.
 */
@Repository
class PlantPublicationStore {

    /** The reviewers' kinds as stored. */
    static final String USER = "USER";
    static final String GROUP = "GROUP";

    /**
     * One plant a document is published to.
     *
     * @param id its identifier
     * @param templateId the document
     * @param plant the plant's code in the organization's list of locations
     * @param groupId the group of plant heads the document was shared with
     * @param madeShare whether the act made the read share itself (false: the group already had one, which taking the
     *        plant back leaves alone)
     * @param publishedAt when the plant was first given the document
     * @param publishedBy who gave it
     */
    record Plant(UUID id, UUID templateId, String plant, UUID groupId, boolean madeShare, Instant publishedAt,
            UUID publishedBy) {

        /** Prints the identity only: the codes are the organization's text. */
        @Override
        public String toString() {
            return "Plant[" + id + "]";
        }
    }

    /**
     * One person or group chosen as a reviewer of a plant of a document.
     *
     * @param id its identifier
     * @param plant the plant's code
     * @param kind {@code USER} or {@code GROUP}
     * @param subjectId the user or the group
     */
    record Reviewer(UUID id, String plant, String kind, UUID subjectId) {

        /** Prints the identity only. */
        @Override
        public String toString() {
            return "Reviewer[" + id + "]";
        }
    }

    private static final String PLANT_COLUMNS = "id, template_id, plant, group_id, made_share, created_at, created_by";
    private static final String LIVE_PLANTS = "tenant_id = :tenant and template_id = :template "
            + "and revoked_at is null and deleted_at is null";
    private static final String LIVE_REVIEWERS = "tenant_id = :tenant and template_id = :template "
            + "and revoked_at is null and deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    PlantPublicationStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    // ---- the plants ----

    /** The plants the document is published to now, oldest first. */
    List<Plant> livePlants(UUID templateId) {
        return jdbc.sql("select " + PLANT_COLUMNS + " from inspection_plant_publication where " + LIVE_PLANTS
                        + " order by created_at, id")
                .param("tenant", tenant()).param("template", templateId).query(PlantPublicationStore::plant).list();
    }

    /** Whether the document is published to a plant whose group is this one (a share to it is still wanted). */
    boolean holdsGroup(UUID templateId, UUID groupId) {
        return jdbc.sql("select exists (select 1 from inspection_plant_publication where " + LIVE_PLANTS
                        + " and group_id = :group)")
                .param("tenant", tenant()).param("template", templateId).param("group", groupId)
                .query(Boolean.class).single();
    }

    Plant insertPlant(UUID templateId, String plant, UUID groupId, boolean madeShare, UUID actor) {
        return jdbc.sql("insert into inspection_plant_publication (tenant_id, template_id, plant, group_id, "
                        + "made_share, created_by, updated_by) values (:tenant, :template, :plant, :group, :made, "
                        + ":actor, :actor) returning " + PLANT_COLUMNS)
                .param("tenant", tenant()).param("template", templateId).param("plant", plant)
                .param("group", groupId).param("made", madeShare).param("actor", actor)
                .query(PlantPublicationStore::plant).single();
    }

    /** Ends a publication to a plant ({@code withdrawn} or {@code changed}); it never changes again. */
    void revokePlant(UUID id, String reason, UUID actor) {
        jdbc.sql("update inspection_plant_publication set revoked_at = now(), revoked_by = :actor, "
                        + "ended_reason = :reason, version = version + 1, updated_by = :actor where id = :id "
                        + "and revoked_at is null and deleted_at is null")
                .param("actor", actor).param("reason", reason).param("id", id).update();
    }

    // ---- the reviewers ----

    /** The reviewers of every plant of the document now, oldest first. */
    List<Reviewer> liveReviewers(UUID templateId) {
        return jdbc.sql("select id, plant, subject_kind, subject_id from inspection_plant_reviewer where "
                        + LIVE_REVIEWERS + " order by created_at, id")
                .param("tenant", tenant()).param("template", templateId)
                .query(PlantPublicationStore::reviewer).list();
    }

    /** The reviewers of one plant of the document now. */
    List<Reviewer> liveReviewers(UUID templateId, String plant) {
        return jdbc.sql("select id, plant, subject_kind, subject_id from inspection_plant_reviewer where "
                        + LIVE_REVIEWERS + " and plant = :plant order by created_at, id")
                .param("tenant", tenant()).param("template", templateId).param("plant", plant)
                .query(PlantPublicationStore::reviewer).list();
    }

    void insertReviewer(UUID templateId, String plant, String kind, UUID subjectId, UUID actor) {
        jdbc.sql("insert into inspection_plant_reviewer (tenant_id, template_id, plant, subject_kind, subject_id, "
                        + "created_by, updated_by) values (:tenant, :template, :plant, :kind, :subject, :actor, "
                        + ":actor)")
                .param("tenant", tenant()).param("template", templateId).param("plant", plant)
                .param("kind", kind).param("subject", subjectId).param("actor", actor).update();
    }

    /** Ends a reviewer's choice ({@code removed} or {@code withdrawn}); it never changes again. */
    void revokeReviewer(UUID id, String reason, UUID actor) {
        jdbc.sql("update inspection_plant_reviewer set revoked_at = now(), revoked_by = :actor, "
                        + "ended_reason = :reason, version = version + 1, updated_by = :actor where id = :id "
                        + "and revoked_at is null and deleted_at is null")
                .param("actor", actor).param("reason", reason).param("id", id).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Plant plant(ResultSet rs, int row) throws SQLException {
        OffsetDateTime at = rs.getObject("created_at", OffsetDateTime.class);
        return new Plant(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getString("plant"), rs.getObject("group_id", UUID.class), rs.getBoolean("made_share"),
                at.toInstant(), rs.getObject("created_by", UUID.class));
    }

    private static Reviewer reviewer(ResultSet rs, int row) throws SQLException {
        return new Reviewer(rs.getObject("id", UUID.class), rs.getString("plant"), rs.getString("subject_kind"),
                rs.getObject("subject_id", UUID.class));
    }
}

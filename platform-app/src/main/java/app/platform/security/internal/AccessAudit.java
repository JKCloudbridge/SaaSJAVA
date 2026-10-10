package app.platform.security.internal;

import app.platform.security.Ability;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the audit records of profiles, access policies, roles and what members hold (ADR-0039): one place that names
 * the events and decides what each carries. Every record names the actor, the target and what changed, in identifiers
 * and short keys: the names of profiles and policies are text an organization chose, so they are bounded (the record
 * cuts them) and the typed notes of individual grants are not written here at all. Records are written in the
 * transaction of the change, so they commit or roll back with it; a refusal is written after its transaction ended.
 */
@Component
class AccessAudit {

    private final AuditRecorder recorder;
    private final TenantContexts contexts;

    AccessAudit(AuditRecorder recorder, TenantContexts contexts) {
        this.recorder = recorder;
        this.contexts = contexts;
    }

    // ---- profiles, policies, roles ----

    void profileCreated(UUID actor, UUID profile, String name, String licenceType, List<String> abilities) {
        write(AuditRecord.of("access.profile.created", AuditOutcome.SUCCESS).forUser(actor)
                .with("profile", profile.toString()).with("name", name).with("licence_type", licenceType)
                .with("abilities", String.join(",", abilities)));
    }

    void profileUpdated(UUID actor, UUID profile, String name, String licenceType, List<String> abilities) {
        write(AuditRecord.of("access.profile.updated", AuditOutcome.SUCCESS).forUser(actor)
                .with("profile", profile.toString()).with("name", name).with("licence_type", licenceType)
                .with("abilities", String.join(",", abilities)));
    }

    void profileDeleted(UUID actor, UUID profile, String name) {
        write(AuditRecord.of("access.profile.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .with("profile", profile.toString()).with("name", name));
    }

    void defaultProfileChanged(UUID actor, UUID profile) {
        write(AuditRecord.of("access.profile.default_changed", AuditOutcome.SUCCESS).forUser(actor)
                .with("profile", profile.toString()));
    }

    void systemProfilesCreated(UUID actorOrNull) {
        write(AuditRecord.of("access.profile.system_created", AuditOutcome.SUCCESS).forUser(actorOrNull));
    }

    void policyCreated(UUID actor, UUID policy, String name, String licenceType, List<String> abilities) {
        write(AuditRecord.of("access.policy.created", AuditOutcome.SUCCESS).forUser(actor)
                .with("policy", policy.toString()).with("name", name)
                .with("licence_type", licenceType == null ? "none" : licenceType)
                .with("abilities", String.join(",", abilities)));
    }

    void policyUpdated(UUID actor, UUID policy, String name, String licenceType, List<String> abilities) {
        write(AuditRecord.of("access.policy.updated", AuditOutcome.SUCCESS).forUser(actor)
                .with("policy", policy.toString()).with("name", name)
                .with("licence_type", licenceType == null ? "none" : licenceType)
                .with("abilities", String.join(",", abilities)));
    }

    void policyDeleted(UUID actor, UUID policy, String name) {
        write(AuditRecord.of("access.policy.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .with("policy", policy.toString()).with("name", name));
    }

    void roleCreated(UUID actor, UUID role, String name, UUID parent) {
        write(AuditRecord.of("access.role.created", AuditOutcome.SUCCESS).forUser(actor)
                .with("role", role.toString()).with("name", name)
                .with("parent", parent == null ? "none" : parent.toString()));
    }

    void roleUpdated(UUID actor, UUID role, String name, UUID parent) {
        write(AuditRecord.of("access.role.updated", AuditOutcome.SUCCESS).forUser(actor)
                .with("role", role.toString()).with("name", name)
                .with("parent", parent == null ? "none" : parent.toString()));
    }

    void roleDeleted(UUID actor, UUID role, String name) {
        write(AuditRecord.of("access.role.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .with("role", role.toString()).with("name", name));
    }

    // ---- groups and permissions on data ----

    void groupCreated(UUID actor, UUID group, String name) {
        write(AuditRecord.of("access.group.created", AuditOutcome.SUCCESS).forUser(actor)
                .with("group", group.toString()).with("name", name));
    }

    void groupUpdated(UUID actor, UUID group, String name) {
        write(AuditRecord.of("access.group.updated", AuditOutcome.SUCCESS).forUser(actor)
                .with("group", group.toString()).with("name", name));
    }

    void groupDeleted(UUID actor, UUID group, String name, int linksEnded) {
        write(AuditRecord.of("access.group.deleted", AuditOutcome.SUCCESS).forUser(actor)
                .with("group", group.toString()).with("name", name).with("links_ended", Integer.toString(linksEnded)));
    }

    /** A person or a group went into a group ({@code kind} is {@code person} or {@code group}). */
    void groupMemberAdded(UUID actor, UUID group, String kind, UUID target) {
        write(AuditRecord.of("access.group.member_added", AuditOutcome.SUCCESS).forUser(actor)
                .with("group", group.toString()).with("kind", kind).with("target", target.toString()));
    }

    void groupMemberRemoved(UUID actorOrNull, UUID group, String kind, UUID target, String why) {
        write(AuditRecord.of("access.group.member_removed", AuditOutcome.SUCCESS).forUser(actorOrNull).because(why)
                .with("group", group.toString()).with("kind", kind).with("target", target.toString()));
    }

    void groupPolicyGiven(UUID actor, UUID group, UUID policy) {
        write(AuditRecord.of("access.group.policy_given", AuditOutcome.SUCCESS).forUser(actor)
                .with("group", group.toString()).with("policy", policy.toString()));
    }

    void groupPolicyTaken(UUID actorOrNull, UUID group, UUID policy, String why) {
        write(AuditRecord.of("access.group.policy_taken", AuditOutcome.SUCCESS).forUser(actorOrNull).because(why)
                .with("group", group.toString()).with("policy", policy.toString()));
    }

    /** The permission matrix of a profile, an access policy or a member was replaced (counts of lines only). */
    void dataAccessChanged(UUID actor, String holderKind, UUID holder, DataAccessStore.Changes changes) {
        write(AuditRecord.of("access.data.changed", AuditOutcome.SUCCESS).forUser(actor)
                .with("holder_kind", holderKind).with("holder", holder.toString())
                .with("objects_added", Integer.toString(changes.objectsAdded()))
                .with("objects_changed", Integer.toString(changes.objectsChanged()))
                .with("objects_removed", Integer.toString(changes.objectsRemoved()))
                .with("fields_added", Integer.toString(changes.fieldsAdded()))
                .with("fields_changed", Integer.toString(changes.fieldsChanged()))
                .with("fields_removed", Integer.toString(changes.fieldsRemoved())));
    }

    /** The permissions on an object or a field that was removed were ended (counts only, ADR-0062). */
    void dataPermissionsForgotten(UUID actorOrNull, String objectKey, String fieldKey, int lines) {
        AuditRecord record = AuditRecord.of("access.data.permissions_ended", AuditOutcome.SUCCESS).forUser(actorOrNull)
                .onObject(fieldKey == null ? objectKey : objectKey + "." + fieldKey, null)
                .with("lines", Integer.toString(lines));
        write(record);
    }

    // ---- what members hold ----

    void memberProfileSet(UUID actorOrNull, UUID membership, UUID profile, String how, boolean licensed) {
        write(AuditRecord.of("access.member.profile_set", AuditOutcome.SUCCESS).forUser(actorOrNull)
                .with("membership", membership.toString()).with("profile", profile.toString()).with("how", how)
                .with("licensed", Boolean.toString(licensed)));
    }

    void memberRoleSet(UUID actor, UUID membership, UUID role) {
        write(AuditRecord.of("access.member.role_set", AuditOutcome.SUCCESS).forUser(actor)
                .with("membership", membership.toString()).with("role", role == null ? "none" : role.toString()));
    }

    void policyAssigned(UUID actor, UUID membership, UUID policy, boolean licenceUsed) {
        write(AuditRecord.of("access.member.policy_assigned", AuditOutcome.SUCCESS).forUser(actor)
                .with("membership", membership.toString()).with("policy", policy.toString())
                .with("licence_used", Boolean.toString(licenceUsed)));
    }

    void policyUnassigned(UUID actorOrNull, UUID membership, UUID policy, String why) {
        write(AuditRecord.of("access.member.policy_unassigned", AuditOutcome.SUCCESS).forUser(actorOrNull).because(why)
                .with("membership", membership.toString()).with("policy", policy.toString()));
    }

    void grantGiven(UUID actor, UUID membership, Ability ability, boolean hasNote) {
        write(AuditRecord.of("access.member.grant_given", AuditOutcome.SUCCESS).forUser(actor)
                .with("membership", membership.toString()).with("ability", ability.key())
                .with("has_note", Boolean.toString(hasNote)));
    }

    void grantRevoked(UUID actor, UUID membership, String ability) {
        write(AuditRecord.of("access.member.grant_revoked", AuditOutcome.SUCCESS).forUser(actor)
                .with("membership", membership.toString()).with("ability", ability));
    }

    void licenceTakenBack(UUID actor, UUID membership) {
        write(AuditRecord.of("access.member.licence_taken_back", AuditOutcome.SUCCESS).forUser(actor)
                .with("membership", membership.toString()));
    }

    /** An action was refused for lack of the ability. Written after the transaction ended. */
    void refused(UUID actor, String action, String reason) {
        write(AuditRecord.of("access.action.refused", AuditOutcome.DENIED).forUser(actor).because(reason)
                .with("action", action));
    }

    private void write(AuditRecord record) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        recorder.record(record.inTenant(tenant));
    }
}

package app.platform.inspection.internal;

import app.platform.data.RecordShares;
import app.platform.inspection.internal.DepartmentAudiences.Resolved;
import app.platform.inspection.internal.PlantPublishRule.Reach;
import app.platform.inspection.internal.PlantPublicationStore.Plant;
import app.platform.inspection.internal.PlantPublicationStore.Reviewer;
import app.platform.inspection.internal.PlantViews.PlantPick;
import app.platform.inspection.internal.PlantViews.PlantPublicationState;
import app.platform.inspection.internal.PlantViews.PlantPublicationView;
import app.platform.inspection.internal.PlantViews.PlantReviewerPick;
import app.platform.inspection.internal.PlantViews.PlantReviewerView;
import app.platform.inspection.internal.PlantViews.PublishPlantsRequest;
import app.platform.inspection.internal.PlantViews.ReviewerChoice;
import app.platform.inspection.internal.PlantViews.ReviewerChoices;
import app.platform.inspection.internal.TemplateViews.AudienceOptionsView;
import app.platform.inspection.internal.TemplateViews.AudiencePick;
import app.platform.security.Grantee;
import app.platform.security.GroupMembers;
import app.platform.security.ShareTargets;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platform.sheets.SheetLifecycle;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The creator of a document publishes it to the plants and chooses the reviewers of each (MC2A, ADR-0260). One act sets
 * the whole: the plants the document is published to, and for each its reviewers. Each act is one transaction: the
 * shares are made or removed, the memory of them is written, the people of a new plant are told, together or not at
 * all.
 *
 * <p><strong>Its own memory.</strong> The access is a read share of the document's record to the group of plant
 * heads an administrator keeps for the plant; {@link PlantPublicationStore} remembers which shares this act made, so
 * taking a plant back removes exactly those and never a share somebody gave by hand. It is apart from Document
 * Management's publish ({@link PublicationStore}): Document Management's unpublish and retire do not touch the
 * plants, and taking a plant back never touches Document Management's shares. The two acts share the one lock of the
 * document, and neither
 * takes a share back that the other still holds.
 *
 * <p><strong>What a change of reviewers does.</strong> The reviewers are read when a review step becomes active, so a
 * change never reaches a request already running: its tasks were made then. Taking a plant back ends its reviewers too;
 * the fill-ins that exist stay as they are.
 *
 * <p><strong>What is never written down</strong>: a plant, a group, a person or a name. The audit records and the
 * metrics carry the document's identifier and counts.
 */
@Service
@Transactional
class PlantPublishing {

    private static final String USER_OBJECT = "User";
    private static final int CHOICES = 20;
    private static final String NOT_ALLOWED = "Only the person who made this document can publish it to the plants.";

    private final PlantPublishRule rule;
    private final PlantPublicationStore store;
    private final PublicationStore publications;
    private final SheetLifecycle sheets;
    private final RecordShares shares;
    private final DepartmentAudiences audiences;
    private final GroupMembers groups;
    private final ShareTargets targets;
    private final List<ReferenceLabels> labels;
    private final PlantPublishedNotices notices;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;

    PlantPublishing(PlantPublishRule rule, PlantPublicationStore store, PublicationStore publications,
            SheetLifecycle sheets, RecordShares shares, DepartmentAudiences audiences, GroupMembers groups,
            ShareTargets targets, List<ReferenceLabels> labels, PlantPublishedNotices notices, AuditRecorder audit,
            TenantContexts contexts, MeterRegistry meters) {
        this.rule = rule;
        this.store = store;
        this.publications = publications;
        this.sheets = sheets;
        this.shares = shares;
        this.audiences = audiences;
        this.groups = groups;
        this.targets = targets;
        this.labels = labels;
        this.notices = notices;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
    }

    /** Whether the document can be published to plants, by this caller, and what it is published to now. */
    @Transactional(readOnly = true)
    PlantPublicationState state(RecordSet screen, UUID templateId) {
        Reach reach = rule.reach(screen, templateId).orElse(null);
        if (reach == null) {
            return new PlantPublicationState(false, false, null, List.of());
        }
        if (!rule.may(reach)) {
            return new PlantPublicationState(true, false, NOT_ALLOWED, List.of());
        }
        String why = why(reach);
        return new PlantPublicationState(true, why == null, why, views(reach));
    }

    /** What the dialog offers: the places that have a group of plant heads; needs the right to publish to plants. */
    @Transactional(readOnly = true)
    AudienceOptionsView options(RecordSet screen, UUID templateId) {
        Reach reach = rule.require(screen, templateId);
        return TemplatePublishing.forSet(reach.plantSet(), audiences.options());
    }

    /** The people and groups a reviewer can be chosen from: those whose name contains the text (bounded). */
    @Transactional(readOnly = true)
    ReviewerChoices reviewerChoices(RecordSet screen, UUID templateId, String text) {
        rule.require(screen, templateId);
        String search = text == null ? "" : text.strip();
        if (search.length() > 80) {
            throw ApiException.validation("text", "Is too long.");
        }
        List<ReviewerChoice> people = users().map(users -> users.options(search, CHOICES).stream()
                .map(option -> new ReviewerChoice(PlantPublicationStore.USER, option.id(), option.label())).toList())
                .orElse(List.of());
        List<ReviewerChoice> found = targets.groups(search, CHOICES).stream()
                .map(target -> new ReviewerChoice(PlantPublicationStore.GROUP, target.id(), target.label())).toList();
        return new ReviewerChoices(people, found);
    }

    /**
     * Sets the plants the document is published to and the reviewers of each.
     *
     * @throws ApiException {@code NOT_FOUND} (not visible), {@code CONFLICT} (not an application that publishes to
     *         plants; retired; no Revision in use), {@code FORBIDDEN} (not the creator and no ability),
     *         {@code VALIDATION_ERROR} (a plant with no group of plant heads, a group with no members, a reviewer who
     *         is no active member or a group that does not exist; the place names the plant or the reviewer)
     */
    PlantPublicationState publish(RecordSet screen, UUID templateId, PublishPlantsRequest request) {
        Reach reach = rule.require(screen, templateId);
        publications.lock(templateId);
        String why = why(reach);
        if (why != null) {
            refuse("publish", "not_now");
            throw new ApiException(ErrorCode.CONFLICT, why);
        }
        RecordSet plantSet = reach.plantSet();
        List<PlantPick> wanted = request.plants();
        List<Resolved> resolved = audiences.resolve(wanted.stream()
                .map(pick -> new AudiencePick(pick.plant(), plantSet.placeDepartment())).toList(), "plants");
        checkReviewers(wanted);
        UUID actor = reach.caller().userId();

        Map<String, Plant> live = new LinkedHashMap<>();
        store.livePlants(templateId).forEach(plant -> live.put(plant.plant(), plant));
        List<Reviewer> liveReviewers = store.liveReviewers(templateId);
        Counts counts = new Counts();
        List<Plant> added = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (int index = 0; index < wanted.size(); index++) {
            PlantPick pick = wanted.get(index);
            UUID group = resolved.get(index).groupId();
            kept.add(pick.plant());
            Plant existing = live.get(pick.plant());
            if (existing != null && !existing.groupId().equals(group)) {
                // The administrator set another group up for the plant: the old publication ends and a new one starts.
                endPlant(reach, existing, "changed", actor, counts);
                existing = null;
            }
            if (existing == null) {
                added.add(addPlant(reach, pick.plant(), group, actor, counts));
            }
            changeReviewers(templateId, pick, liveReviewers, actor, counts);
        }
        for (Plant gone : live.values()) {
            if (kept.contains(gone.plant())) {
                continue;
            }
            endPlant(reach, gone, "withdrawn", actor, counts);
            counts.withdrawn++;
            for (Reviewer reviewer : liveReviewers) {
                if (reviewer.plant().equals(gone.plant())) {
                    store.revokeReviewer(reviewer.id(), "withdrawn", actor);
                    counts.reviewersRemoved++;
                }
            }
        }
        int told = 0;
        for (Plant plant : added) {
            told += notices.tell(plantSet, reach.header(), plant.groupId(), plant.id(), actor);
        }
        audit.record(AuditRecord.of("inspection.template.plants.published", AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(contexts.require().tenantId()).onObject(plantSet.template(), templateId.toString())
                .with("plants", Integer.toString(wanted.size())).with("plants_added", Integer.toString(added.size()))
                .with("plants_withdrawn", Integer.toString(counts.withdrawn))
                .with("shares_made", Integer.toString(counts.sharesMade))
                .with("shares_ended", Integer.toString(counts.sharesEnded))
                .with("reviewers_added", Integer.toString(counts.reviewersAdded))
                .with("reviewers_removed", Integer.toString(counts.reviewersRemoved))
                .with("told", Integer.toString(told)));
        meters.counter("platform.inspection.plants", "act", "publish", "result", "ok").increment();
        return new PlantPublicationState(true, true, null, views(reach));
    }

    // ---- the plants ----

    private Plant addPlant(Reach reach, String plant, UUID group, UUID actor, Counts counts) {
        RecordShares.Granted granted = shares.grantRead(reach.plantSet().template(), reach.templateId(),
                new Grantee(Grantee.Kind.GROUP, group), actor);
        boolean madeIt = granted == RecordShares.Granted.CREATED;
        if (madeIt) {
            counts.sharesMade++;
        }
        return store.insertPlant(reach.templateId(), plant, group, madeIt, actor);
    }

    /**
     * Ends a publication to a plant and takes its share back when this act made it and nothing else holds the group:
     * another plant of the document, or Document Management's own publish, may still need it.
     */
    private void endPlant(Reach reach, Plant plant, String reason, UUID actor, Counts counts) {
        store.revokePlant(plant.id(), reason, actor);
        if (!plant.madeShare() || store.holdsGroup(reach.templateId(), plant.groupId())
                || publications.holdsGroup(reach.templateId(), plant.groupId())) {
            return;
        }
        if (shares.revoke(reach.plantSet().template(), reach.templateId(),
                new Grantee(Grantee.Kind.GROUP, plant.groupId()), actor)) {
            counts.sharesEnded++;
        }
    }

    // ---- the reviewers ----

    private void changeReviewers(UUID templateId, PlantPick pick, List<Reviewer> liveReviewers, UUID actor,
            Counts counts) {
        Set<String> wanted = pick.reviewers().stream().map(reviewer -> key(reviewer.kind(), reviewer.id()))
                .collect(Collectors.toSet());
        Set<String> present = new HashSet<>();
        for (Reviewer reviewer : liveReviewers) {
            if (!reviewer.plant().equals(pick.plant())) {
                continue;
            }
            String name = key(reviewer.kind(), reviewer.subjectId());
            if (wanted.contains(name)) {
                present.add(name);
            } else {
                store.revokeReviewer(reviewer.id(), "removed", actor);
                counts.reviewersRemoved++;
            }
        }
        for (PlantReviewerPick reviewer : pick.reviewers()) {
            if (!present.contains(key(reviewer.kind(), reviewer.id()))) {
                store.insertReviewer(templateId, pick.plant(), reviewer.kind(), reviewer.id(), actor);
                counts.reviewersAdded++;
            }
        }
    }

    /** Every reviewer is an active member or an existing group, and nobody is listed twice for a plant. */
    private void checkReviewers(List<PlantPick> wanted) {
        Set<UUID> userIds = new HashSet<>();
        wanted.forEach(pick -> pick.reviewers().stream().filter(one -> PlantPublicationStore.USER.equals(one.kind()))
                .forEach(one -> userIds.add(one.id())));
        Set<UUID> active = userIds.isEmpty() ? Set.of() : users().map(one -> one.labels(userIds).keySet())
                .orElse(Set.of());
        for (int plant = 0; plant < wanted.size(); plant++) {
            Set<String> seen = new HashSet<>();
            List<PlantReviewerPick> reviewers = wanted.get(plant).reviewers();
            for (int index = 0; index < reviewers.size(); index++) {
                PlantReviewerPick one = reviewers.get(index);
                String place = "plants[" + plant + "].reviewers[" + index + "]";
                if (!seen.add(key(one.kind(), one.id()))) {
                    throw ApiException.validation(place, "This reviewer is listed twice.");
                }
                if (PlantPublicationStore.USER.equals(one.kind()) ? !active.contains(one.id())
                        : !groups.exists(one.id())) {
                    throw ApiException.validation(place, PlantPublicationStore.USER.equals(one.kind())
                            ? "This person is not an active member of the organization."
                            : "This group does not exist.");
                }
            }
        }
    }

    // ---- the view ----

    private List<PlantPublicationView> views(Reach reach) {
        List<Plant> plants = store.livePlants(reach.templateId());
        if (plants.isEmpty()) {
            return List.of();
        }
        List<Reviewer> reviewers = store.liveReviewers(reach.templateId());
        Map<String, String> places = audiences.labels(Templates.LOCATION);
        Set<UUID> userIds = reviewers.stream().filter(one -> PlantPublicationStore.USER.equals(one.kind()))
                .map(Reviewer::subjectId).collect(Collectors.toSet());
        Set<UUID> groupIds = reviewers.stream().filter(one -> PlantPublicationStore.GROUP.equals(one.kind()))
                .map(Reviewer::subjectId).collect(Collectors.toSet());
        Map<UUID, String> people = userIds.isEmpty() ? Map.of() : users().map(one -> one.labels(userIds))
                .orElse(Map.of());
        Map<UUID, String> named = groupIds.isEmpty() ? Map.of() : targets.groupLabels(groupIds);
        List<PlantPublicationView> views = new ArrayList<>();
        for (Plant plant : plants) {
            List<PlantReviewerView> mine = reviewers.stream().filter(one -> one.plant().equals(plant.plant()))
                    .map(one -> new PlantReviewerView(one.kind(), one.subjectId(),
                            PlantPublicationStore.USER.equals(one.kind()) ? people.get(one.subjectId())
                                    : named.get(one.subjectId()))).toList();
            views.add(new PlantPublicationView(plant.plant(), places.getOrDefault(plant.plant(), plant.plant()),
                    plant.publishedAt(), groups.exists(plant.groupId()), mine));
        }
        return views;
    }

    /** The words that say why the document cannot be published to plants now, or null when it can. */
    private String why(Reach reach) {
        if (Boolean.TRUE.equals(reach.header().fields().get(Templates.RETIRED))) {
            return "This document is retired, so it cannot be published to plants.";
        }
        SheetLifecycle.Summary summary = sheets.summaries(reach.plantSet().template(), List.of(reach.templateId()))
                .get(reach.templateId());
        if (summary == null || summary.active() == 0) {
            return "This document has no Revision in use yet. It is published to plants after it was published in "
                    + "Document Management.";
        }
        return null;
    }

    private Optional<ReferenceLabels> users() {
        return labels.stream().filter(one -> USER_OBJECT.equals(one.object())).findFirst();
    }

    private static String key(String kind, UUID id) {
        return kind + ":" + id;
    }

    private void refuse(String act, String reason) {
        meters.counter("platform.inspection.plants", "act", act, "result", reason).increment();
    }

    /** What one act did, in numbers (the only thing the audit records of it say). */
    private static final class Counts {
        private int sharesMade;
        private int sharesEnded;
        private int withdrawn;
        private int reviewersAdded;
        private int reviewersRemoved;
    }
}

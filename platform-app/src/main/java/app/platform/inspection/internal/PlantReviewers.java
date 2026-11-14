package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.sharedkernel.approval.PublicationReviewers;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The reviewers chosen for a fill-in, answered to the approval engine when a review step becomes active (MC2A,
 * ADR-0260). The fill-in's header names its document and its plant, both written once by the platform when the fill-in
 * was started; the choice is what the creator set for that document and plant at that moment
 * ({@link PlantPublicationStore}). Nothing here decides who may decide: the approval engine asks the people named, in
 * its own way (active member, licence, not the submitter).
 *
 * <p>A fill-in with no plant, a plant with no reviewer and a header that cannot be found give nobody, and the engine
 * says so in its own fixed words at Submit. The choice itself is never written to a log.
 */
@Component
class PlantReviewers implements PublicationReviewers {

    private final RecordSets sets;
    private final BackgroundRecords records;
    private final PlantPublicationStore store;

    PlantReviewers(RecordSets sets, BackgroundRecords records, PlantPublicationStore store) {
        this.sets = sets;
        this.records = records;
        this.store = store;
    }

    @Override
    public boolean serves(String object) {
        return sets.fills().contains(object);
    }

    @Override
    public Reviewers reviewersOf(String object, UUID recordId) {
        DataRecord header = records.find(object, recordId).orElse(null);
        if (header == null) {
            return Reviewers.none();
        }
        UUID templateId = Fills.templateOf(header.fields().get(Fills.TEMPLATE_ID));
        Object plant = header.fields().get(Fills.PLANT);
        if (templateId == null || !(plant instanceof String code) || code.isBlank()) {
            return Reviewers.none();
        }
        Set<UUID> people = new HashSet<>();
        Set<UUID> groups = new HashSet<>();
        for (PlantPublicationStore.Reviewer reviewer : store.liveReviewers(templateId, code)) {
            (PlantPublicationStore.USER.equals(reviewer.kind()) ? people : groups).add(reviewer.subjectId());
        }
        return new Reviewers(people, groups);
    }
}

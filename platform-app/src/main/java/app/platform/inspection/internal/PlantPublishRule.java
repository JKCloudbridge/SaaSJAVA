package app.platform.inspection.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.security.Ability;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may publish a document to its plants, asked in one place (MC2A, ADR-0260): the view of the dialog and every act
 * ask the same questions, so a hidden button is never the rule. The cluster has no rule of its own about who sees a
 * document: the member must see it by the record store's answer ({@link TemplateAccess#visible}), as for every act on a
 * template. On top of that:
 *
 * <ul>
 *   <li>the document must carry an application that publishes to places and that the organization has
 *   ({@link RecordSets#plantSetOf});</li>
 *   <li>the member made the document (they own it) or holds the ability {@link Ability#SHEETS_PUBLISH_PLANTS}, which
 *   the Organization administrator holds as every ability. It is not the ability to publish in Document Management and
 *   no policy of the Publisher gives it.</li>
 * </ul>
 *
 * <p>Whether a Revision is in use and whether the document is retired are the act's own conditions: they are facts
 * about the document, not about the person, and they are said in words.
 */
@Component
class PlantPublishRule {

    private static final String NOT_AVAILABLE = "This document is not published to plants.";

    /**
     * What an act on the plants of a document works with.
     *
     * @param caller the member
     * @param header the document's record, as the platform reads it
     * @param plantSet the set of the application the document was routed to (its options say where it publishes)
     */
    record Reach(Caller caller, DataRecord header, RecordSet plantSet) {

        UUID templateId() {
            return header.id();
        }
    }

    private final TemplateAccess access;
    private final BackgroundRecords records;
    private final RecordSets sets;

    PlantPublishRule(TemplateAccess access, BackgroundRecords records, RecordSets sets) {
        this.access = access;
        this.records = records;
        this.sets = sets;
    }

    /**
     * The member's reach to the plants of a document they can see, or empty when the document does not belong to an
     * application that publishes to plants.
     *
     * @throws ApiException {@code NOT_FOUND} when the member cannot see the document
     */
    Optional<Reach> reach(RecordSet screen, UUID templateId) {
        Caller caller = access.visible(screen, templateId);
        DataRecord header = records.find(screen.template(), templateId)
                .orElseThrow(() -> ApiException.notFound("This template does not exist."));
        return sets.plantSetOf(header.fields().get(Templates.APPLICATION))
                .map(plantSet -> new Reach(caller, header, plantSet));
    }

    /** Whether the member may act on the plants of this document: they made it, or hold the ability. */
    boolean may(Reach reach) {
        return reach.caller().userId().equals(reach.header().ownerId())
                || access.has(reach.caller(), Ability.SHEETS_PUBLISH_PLANTS);
    }

    /**
     * The member's reach, for an act.
     *
     * @throws ApiException {@code NOT_FOUND} (the document is not visible), {@code CONFLICT} (it does not belong to an
     *         application that publishes to plants), {@code FORBIDDEN} (the member neither made it nor holds the
     *         ability)
     */
    Reach require(RecordSet screen, UUID templateId) {
        Reach reach = reach(screen, templateId).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, NOT_AVAILABLE));
        if (!may(reach)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return reach;
    }
}

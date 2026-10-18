package app.platform.approval.internal;

import app.platform.approval.internal.ApprovalCaller.Member;
import app.platform.approval.internal.ApprovalStore.DelegationRow;
import app.platform.metadata.Metadata;
import app.platform.security.Ability;
import app.platformapi.ApiException;
import app.platformapi.ApprovalDelegationView;
import app.platformapi.ApprovalPersonView;
import app.platformapi.CreateApprovalDelegationRequest;
import app.platformapi.ErrorCode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delegations (ADR-0151): a person names a delegate and a range of days; while it is in force, the tasks that would be
 * given to the delegator are also given to the delegate. It is its own record and never an edit of a process. Read when
 * a
 * step becomes active ({@link ApproverResolver}); nothing runs on a clock to start or end one.
 *
 * <p>Any member makes a delegation for themselves; a member who holds {@code approvals.manage} makes one for anybody.
 * The
 * delegate must be an active member who holds the licence their profile needs, no delegation is to oneself, a range is
 * bounded, the first day is not in the past in the zone of the delegation, and a person has a bounded number of current
 * or future delegations. Every change is audited by identifiers and dates.
 */
@Service
@Transactional
class ApprovalDelegations {

    private static final int LIST_LIMIT = 200;

    private final ApprovalStore store;
    private final ApproverResolver resolver;
    private final ApprovalCaller callers;
    private final ApprovalAudit audit;
    private final Metadata metadata;
    private final ApprovalProperties properties;
    private final Clock clock;

    ApprovalDelegations(ApprovalStore store, ApproverResolver resolver, ApprovalCaller callers, ApprovalAudit audit,
            Metadata metadata, ApprovalProperties properties, Clock clock) {
        this.store = store;
        this.resolver = resolver;
        this.callers = callers;
        this.audit = audit;
        this.metadata = metadata;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The delegations of one person, or of every person.
     *
     * @param delegator whose delegations; null for the caller's own
     * @param everybody all of them (needs {@code approvals.manage})
     */
    @Transactional(readOnly = true)
    List<ApprovalDelegationView> list(Member member, UUID delegator, boolean everybody) {
        UUID whose = delegator == null ? member.userId() : delegator;
        if (everybody) {
            callers.require(member, Ability.APPROVALS_MANAGE);
            return views(store.delegations(null, LIST_LIMIT));
        }
        if (!whose.equals(member.userId())) {
            callers.require(member, Ability.APPROVALS_MANAGE);
        }
        return views(store.delegations(whose, LIST_LIMIT));
    }

    ApprovalDelegationView create(Member member, CreateApprovalDelegationRequest request) {
        UUID delegator = request.delegatorId() == null ? member.userId() : request.delegatorId();
        if (!delegator.equals(member.userId())) {
            callers.require(member, Ability.APPROVALS_MANAGE);
        }
        if (delegator.equals(request.delegateId())) {
            throw ApiException.validation("delegateId", "A person cannot delegate to themselves.");
        }
        ZoneId zone = zone(request.timeZone());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (request.startsOn().isBefore(today)) {
            throw ApiException.validation("startsOn", "The first day cannot be in the past.");
        }
        if (request.endsOn().isBefore(request.startsOn())) {
            throw ApiException.validation("endsOn", "The last day cannot be before the first.");
        }
        int days = properties.limits().maxDelegationDays();
        if (ChronoUnit.DAYS.between(request.startsOn(), request.endsOn()) + 1 > days) {
            throw ApiException.validation("endsOn", "A delegation lasts at most " + days + " days.");
        }
        String object = blank(request.objectApiName());
        String process = blank(request.processApiName());
        if ((object == null) != (process == null)) {
            throw ApiException.validation("processApiName", "Name both the object and the process, or neither.");
        }
        if (process != null && metadata.approvalProcesses(object).stream()
                .noneMatch(found -> found.apiName().equals(process))) {
            throw ApiException.validation("processApiName", "This object has no approval process with this name.");
        }
        ApproverResolution.Person delegate = resolver.standingOf(request.delegateId());
        if (delegate == null || !delegate.active() || !delegate.licensed()) {
            throw ApiException.validation("delegateId", "This person cannot be asked: they are not an active member "
                    + "who holds a licence.");
        }
        ApproverResolution.Person owner = resolver.standingOf(delegator);
        if (owner == null || !owner.active()) {
            throw ApiException.validation("delegatorId", "This person is not an active member.");
        }
        if (store.currentDelegations(delegator, today) >= properties.limits().maxDelegationsPerPerson()) {
            throw new ApiException(ErrorCode.CONFLICT, "A person has at most "
                    + properties.limits().maxDelegationsPerPerson() + " current or future delegations.");
        }
        DelegationRow row = store.insertDelegation(delegator, request.delegateId(), object, process,
                request.startsOn(), request.endsOn(), zone.getId(), member.userId());
        audit.delegation("created", member.userId(), delegator, request.delegateId(), object, process,
                request.startsOn().toString(), request.endsOn().toString());
        return views(List.of(row)).get(0);
    }

    void revoke(Member member, UUID id) {
        DelegationRow row = store.delegation(id).orElseThrow(() -> ApiException.notFound(
                "This delegation does not exist."));
        if (!row.delegatorId().equals(member.userId())) {
            callers.require(member, Ability.APPROVALS_MANAGE);
        }
        if (!store.revokeDelegation(id, row.version(), member.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.delegation("revoked", member.userId(), row.delegatorId(), row.delegateId(), row.processObject(),
                row.processApiName(), row.startsOn().toString(), row.endsOn().toString());
    }

    private List<ApprovalDelegationView> views(List<DelegationRow> rows) {
        Set<UUID> people = new LinkedHashSet<>();
        rows.forEach(row -> {
            people.add(row.delegatorId());
            people.add(row.delegateId());
        });
        Map<UUID, String> names = resolver.names(people);
        return rows.stream().map(row -> new ApprovalDelegationView(row.id(),
                new ApprovalPersonView(row.delegatorId(), names.get(row.delegatorId())),
                new ApprovalPersonView(row.delegateId(), names.get(row.delegateId())), row.processObject(),
                row.processApiName(), row.startsOn(), row.endsOn(), row.timeZone(), resolver.inForce(row))).toList();
    }

    private static ZoneId zone(String asked) {
        try {
            return ZoneId.of(asked.strip());
        } catch (DateTimeException unknown) {
            throw ApiException.validation("timeZone", "Use a time zone of the IANA database, for example UTC or "
                    + "Europe/Berlin.");
        }
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}

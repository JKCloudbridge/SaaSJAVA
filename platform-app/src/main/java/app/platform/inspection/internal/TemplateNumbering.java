package app.platform.inspection.internal;

import app.platform.data.NumberSeries;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.inspection.internal.TemplateViews.NumberingInfoRequest;
import app.platform.inspection.internal.TemplateViews.NumberingInfoView;
import app.platform.security.Decisions;
import app.platform.security.FieldAction;
import app.platform.security.ObjectAction;
import app.platformapi.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The numbering information the template form asks while a person types the identification and its parts (FA11B,
 * ADR-0206). It reads the format that applies and the state of the counter and takes nothing: no number is reserved,
 * and the Save decides. The form holds no pattern of its own, so a format an administrator changes in Setup,
 * Numbering is followed at once.
 *
 * <p>Who may see what: a caller who can read templates learns which parts the format asks for and the number written
 * so far; only a caller who may set the identification (the people who may type the serial) learns the next serial and
 * the whole number of a counted format. Nothing typed is logged or audited.
 */
@Service
@Transactional(readOnly = true)
class TemplateNumbering {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,10}");

    private final TemplateAccess access;
    private final NumberSeries numbers;
    private final Decisions decisions;

    TemplateNumbering(TemplateAccess access, NumberSeries numbers, Decisions decisions) {
        this.access = access;
        this.numbers = numbers;
        this.decisions = decisions;
    }

    NumberingInfoView info(NumberingInfoRequest request) {
        Caller caller = access.member();
        if (!decisions.can(caller.membershipId(), Templates.OBJECT, ObjectAction.READ).allowed()) {
            throw ApiException.notFound("This page does not exist.");
        }
        boolean canStart = decisions.can(caller.membershipId(), Templates.OBJECT, Templates.CATEGORY,
                FieldAction.EDIT).allowed();
        Map<String, Object> values = new HashMap<>();
        put(values, Templates.CATEGORY, request.category());
        put(values, Templates.PART_A, request.partA());
        put(values, Templates.PART_B, request.partB());
        Optional<NumberSeries.Plan> plan = values.containsKey(Templates.CATEGORY)
                ? numbers.plan(Templates.SERIES, values) : Optional.empty();
        if (plan.isEmpty()) {
            return new NumberingInfoView(false, false, false, false, 0, "", false, false, canStart, null, null);
        }
        NumberSeries.Plan found = plan.get();
        boolean started = found.next().isPresent();
        boolean mayShowNext = canStart || !found.counted();
        return new NumberingInfoView(true, found.fields().contains(Templates.PART_A),
                found.fields().contains(Templates.PART_B), found.counted(), found.width(), found.prefix(),
                found.complete(), started, canStart,
                started && canStart ? found.next().getAsLong() : null,
                mayShowNext ? found.number().orElse(null) : null);
    }

    /** Only a value that can be part of a number is passed on; anything else counts as not typed yet. */
    private static void put(Map<String, Object> values, String field, String value) {
        if (value != null && CODE.matcher(value).matches()) {
            values.put(field, value);
        }
    }
}

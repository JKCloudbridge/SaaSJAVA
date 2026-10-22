package app.platform.approval.internal;

import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PlatformObjects;
import app.platform.sharedkernel.approval.ApprovalHooks;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The hooks of this release, collected once when the application starts and checked (FA4A, ADR-0187). A mistake stops
 * the start with a message that names the object and never repeats anything else, so a build finds it and an
 * organization never does:
 *
 * <ul>
 * <li>a second hook for an object that has one;</li>
 * <li>an object name or a field name that is not shaped like one;</li>
 * <li>for an object the platform itself defines: a field in {@code writes()} that the object does not have, or that is
 * not a field only the platform writes (ADR-0183). An object an organization makes does not exist at start, so there
 * the narrow writer's refusal of any undeclared name is the only line.</li>
 * </ul>
 *
 * <p>An object with no hook costs one map lookup. Nothing here changes while the application runs.
 */
@Component
final class ApprovalHookRegistry {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final int MOST_FIELDS = 10;

    private final Map<String, ApprovalHooks> byObject;

    @Autowired
    ApprovalHookRegistry(ObjectProvider<ApprovalHooks> hooks, PlatformObjects platform) {
        this(hooks.orderedStream().toList(), platform);
    }

    /** The hooks given, checked (the tests hand it any combination). */
    ApprovalHookRegistry(List<ApprovalHooks> hooks, PlatformObjects platform) {
        Map<String, ApprovalHooks> checked = new LinkedHashMap<>();
        for (ApprovalHooks hook : hooks) {
            String object = hook.object();
            if (object == null || !NAME.matcher(object).matches()) {
                throw new IllegalStateException("an approval hook names an object that is not shaped like one");
            }
            check(hook, object, platform);
            if (checked.put(object, hook) != null) {
                throw new IllegalStateException("object '" + object + "' has more than one approval hook");
            }
        }
        this.byObject = Map.copyOf(checked);
    }

    /** The hook of the object, if it has one. */
    Optional<ApprovalHooks> of(String objectApiName) {
        return Optional.ofNullable(byObject.get(objectApiName));
    }

    private static void check(ApprovalHooks hook, String object, PlatformObjects platform) {
        List<String> writes = hook.writes();
        if (writes == null || writes.size() > MOST_FIELDS) {
            throw new IllegalStateException("the approval hook of '" + object + "' lists too many fields to write");
        }
        Set<String> seen = new HashSet<>();
        ObjectDefinition defined = platform.named(object).orElse(null);
        for (String field : writes) {
            if (field == null || !NAME.matcher(field).matches() || !seen.add(field)) {
                throw new IllegalStateException("the approval hook of '" + object
                        + "' lists a field name that is not shaped like one, or lists a field twice");
            }
            if (defined != null) {
                FieldDefinition found = defined.field(field).orElse(null);
                if (found == null || !found.platformWritten()) {
                    throw new IllegalStateException("the approval hook of '" + object + "' writes field '" + field
                            + "', which the object does not have as a field only the platform writes");
                }
            }
        }
    }
}

package app.platform.solution.internal;

import app.platform.licensing.Entitlements;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.solution.ApplicationSettings;
import app.platform.solution.internal.ApplicationSettingViews.ApplicationSettingView;
import app.platform.solution.internal.ApplicationSettingViews.ApplicationSettingsGroupView;
import app.platform.solution.internal.ApplicationSettingViews.ApplicationSettingsView;
import app.platform.solution.internal.ApplicationSettingViews.SaveApplicationSettingRequest;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an organization may choose for each application it has, and what an application asks for (MC2B, ADR-0261).
 * The solution files declare the settings and their defaults; an organization with no row of its own works on the
 * defaults. Managing needs the ability {@code application-settings.manage}; asking {@link #value} is the platform's own
 * work and needs none.
 *
 * <p>A setting is offered only for an application the organization has (its plan feature is on): the same answer as
 * for an unknown application, so nothing is learned from the difference. The value is the organization's: the audit
 * says the application's key, the setting's key and the kind of change, never the value.
 */
@Service
@Transactional
class ApplicationSettingService implements ApplicationSettings {

    private static final String NOT_OFFERED = "This setting does not exist.";
    private static final String CHANGED = "This setting was changed by someone else. Reload and try again.";

    private final Solutions solutions;
    private final Entitlements entitlements;
    private final ApplicationSettingStore store;
    private final Permissions permissions;
    private final TenantContexts contexts;
    private final AuditRecorder audit;

    ApplicationSettingService(Solutions solutions, Entitlements entitlements, ApplicationSettingStore store,
            Permissions permissions, TenantContexts contexts, AuditRecorder audit) {
        this.solutions = solutions;
        this.entitlements = entitlements;
        this.store = store;
        this.permissions = permissions;
        this.contexts = contexts;
        this.audit = audit;
    }

    /** A declared setting with the application and bundle that declare it. */
    private record Declared(SolutionBundle bundle, SolutionBundle.Application application,
            SolutionBundle.Setting setting) {
    }

    // ---- what the platform asks ----

    @Override
    @Transactional(readOnly = true)
    public OptionalInt value(String application, String key) {
        Optional<Declared> declared = declared(application, key);
        if (declared.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(store.find(application, key).map(ApplicationSettingStore.Chosen::value)
                .orElse(declared.get().setting().defaultValue()));
    }

    // ---- what the screen asks (Setup, Application settings) ----

    @Transactional(readOnly = true)
    ApplicationSettingsView view() {
        requireManager();
        return currentView();
    }

    ApplicationSettingsView save(SaveApplicationSettingRequest request) {
        UUID actor = requireManager();
        Declared declared = offered(request.application(), request.key());
        if (!declared.setting().kind().accepts(request.value())) {
            throw ApiException.validation("value", "Give a whole number from " + declared.setting().kind().least()
                    + " to " + declared.setting().kind().most() + ".");
        }
        Optional<ApplicationSettingStore.Chosen> existing = store.find(request.application(), request.key());
        boolean done;
        String kind;
        if (existing.isEmpty()) {
            done = request.version() == null
                    && store.insert(request.application(), request.key(), request.value(), actor);
            kind = "chosen";
        } else {
            done = request.version() != null && request.version() == existing.get().version()
                    && store.update(existing.get().id(), request.value(), request.version(), actor);
            kind = "changed";
        }
        if (!done) {
            throw new ApiException(ErrorCode.CONFLICT, CHANGED);
        }
        record(kind, request.application(), request.key(), actor);
        return currentView();
    }

    ApplicationSettingsView reset(String application, String key, long version) {
        UUID actor = requireManager();
        offered(application, key);
        ApplicationSettingStore.Chosen existing = store.find(application, key)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, CHANGED));
        if (!store.remove(existing.id(), version, actor)) {
            throw new ApiException(ErrorCode.CONFLICT, CHANGED);
        }
        record("reset", application, key, actor);
        return currentView();
    }

    // ---- the pieces ----

    private ApplicationSettingsView currentView() {
        List<ApplicationSettingsGroupView> groups = new ArrayList<>();
        for (SolutionBundle bundle : solutions.bundles()) {
            if (bundle.settings().isEmpty() || !entitlements.enabled(bundle.feature())) {
                continue;
            }
            for (SolutionBundle.Application application : bundle.applications()) {
                List<ApplicationSettingView> views = new ArrayList<>();
                for (SolutionBundle.Setting setting : bundle.settings()) {
                    Optional<ApplicationSettingStore.Chosen> chosen = store.find(application.apiName(),
                            setting.key());
                    views.add(new ApplicationSettingView(setting.key(), setting.label(), setting.description(),
                            setting.kind().name(), chosen.map(ApplicationSettingStore.Chosen::value)
                                    .orElse(setting.defaultValue()), setting.defaultValue(),
                            setting.kind().least(), setting.kind().most(), chosen.isPresent(),
                            chosen.map(ApplicationSettingStore.Chosen::version).orElse(null)));
                }
                groups.add(new ApplicationSettingsGroupView(application.apiName(), application.label(), views));
            }
        }
        return new ApplicationSettingsView(groups);
    }

    /** The declaration of a setting of an application the organization has; empty for anything else. */
    private Optional<Declared> declared(String application, String key) {
        if (application == null || key == null) {
            return Optional.empty();
        }
        for (SolutionBundle bundle : solutions.bundles()) {
            for (SolutionBundle.Application one : bundle.applications()) {
                if (!one.apiName().equals(application)) {
                    continue;
                }
                for (SolutionBundle.Setting setting : bundle.settings()) {
                    if (setting.key().equals(key) && entitlements.enabled(bundle.feature())) {
                        return Optional.of(new Declared(bundle, one, setting));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The same "not found" for a setting that does not exist and one the organization does not have. */
    private Declared offered(String application, String key) {
        return declared(application, key).orElseThrow(() -> ApiException.notFound(NOT_OFFERED));
    }

    private UUID requireManager() {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NOT_OFFERED));
        if (!permissions.has(context.membershipId(), Ability.APPLICATION_SETTINGS_MANAGE)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return context.userId();
    }

    private void record(String kind, String application, String key, UUID actor) {
        audit.record(AuditRecord.of("application.setting." + kind, AuditOutcome.SUCCESS).forUser(actor)
                .inTenant(contexts.require().tenantId()).with("application", application).with("setting", key));
    }
}

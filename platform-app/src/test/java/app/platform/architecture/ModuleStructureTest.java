package app.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.PlatformApplication;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Fails the build when a module depends on another module it is not allowed to use, reaches into
 * another module's internals, or when modules form a cycle.
 */
@Tag("architecture")
class ModuleStructureTest {

    /** The logical modules of the platform. Adding or removing one is a deliberate, reviewed change. */
    static final Set<String> EXPECTED_MODULES = Set.of(
            "tenant", "identity", "licensing", "security", "metadata", "data", "application",
            "workflow", "approval", "notification", "integration", "audit", "platformadmin",
            "web", "observability", "outbox", "sharedkernel");

    private final ApplicationModules modules = ApplicationModules.of(PlatformApplication.class);

    @Test
    void modulesMatchTheDeclaredModuleList() {
        Set<String> actual = new java.util.TreeSet<>();
        modules.forEach(module -> actual.add(module.getIdentifier().toString()));

        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);
    }

    @Test
    void noModuleViolatesDeclaredDependenciesEncapsulationOrCycles() {
        modules.verify();
    }

    @Test
    void everyBusinessModuleDeclaresItsAllowedDependenciesExplicitly() throws ClassNotFoundException {
        List<String> undeclared = EXPECTED_MODULES.stream()
                .filter(name -> !"sharedkernel".equals(name))
                .filter(name -> {
                    Package pkg = packageOf(name);
                    ApplicationModule annotation = pkg.getAnnotation(ApplicationModule.class);
                    return annotation == null || annotation.allowedDependencies().length == 0;
                })
                .toList();

        assertThat(undeclared).as("modules without explicit allowedDependencies").isEmpty();
    }

    private static Package packageOf(String module) {
        try {
            return Class.forName("app.platform." + module + ".package-info").getPackage();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing package-info for module " + module, e);
        }
    }
}

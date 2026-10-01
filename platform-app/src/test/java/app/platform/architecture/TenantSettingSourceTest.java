package app.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The tenant reaches the database session in exactly one place (ADR-0015): the transaction hook of the tenant module.
 * Code that wrote the setting by hand somewhere else could set a tenant the context does not know about, which is the
 * mistake row level security exists to contain. The setting names appear in SQL migrations (policies read them) and
 * in that one class, nowhere else in the application's code.
 */
@Tag("architecture")
class TenantSettingSourceTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final String HOOK = "TenantSessionListener.java";

    @Test
    void onlyTheTransactionHookOfTheTenantModuleNamesTheDatabaseSettings() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals(HOOK))
                    .filter(TenantSettingSourceTest::namesASetting)
                    .toList();
        }

        assertThat(offenders).as("files that name app.current_tenant or app.system_scope").isEmpty();
    }

    @Test
    void theHookItselfDoesNameThem() throws IOException {
        // Guards the guard: if the setting names ever changed, the scan above would silently stop proving anything.
        Path hook = SOURCES.resolve("app/platform/tenant/internal/" + HOOK);

        assertThat(Files.readString(hook)).contains("app.current_tenant").contains("app.system_scope");
    }

    private static boolean namesASetting(Path path) {
        try {
            String content = Files.readString(path);
            return content.contains("app.current_tenant") || content.contains("app.system_scope");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

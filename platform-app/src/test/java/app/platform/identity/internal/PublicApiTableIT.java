package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The closed table of public operations is the set of controllers under the public prefix, and nothing else (Sprint 30,
 * ADR-0244): an operation cannot be added to the controllers without the table (and with it the scope seam), and the
 * table cannot name an operation that no controller answers. Anything under the prefix that is not in the table is
 * not mapped at all.
 */
@PlatformIntegrationTest
class PublicApiTableIT {

    @Autowired
    private RequestMappingHandlerMapping mappings;

    @Test
    void theControllersUnderThePublicPrefixAreExactlyTheRowsOfTheTable() {
        Set<String> mapped = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (var pattern : info.getPathPatternsCondition().getPatterns()) {
                if (!pattern.getPatternString().startsWith("/api/public/")) {
                    continue;
                }
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                assertThat(methods).as(pattern.getPatternString() + " names its method").isNotEmpty();
                methods.forEach(method -> mapped.add(method.name() + " " + pattern.getPatternString()
                        .replaceAll("\\{[a-zA-Z]+}", "{x}")));
            }
        }
        Set<String> table = new TreeSet<>();
        PublicApiAccess.OPERATIONS.forEach(operation -> table.add(operation.method() + " "
                + operation.template().replaceAll("\\{[a-zA-Z]+}", "{x}")));

        assertThat(mapped).containsExactlyElementsOf(table);
    }

    @Test
    void nothingMappedUnderTheInternalPrefixIsOnThePublicTable() {
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            info.getPathPatternsCondition().getPatterns().forEach(pattern -> {
                if (pattern.getPatternString().startsWith("/api/v1/")) {
                    assertThat(PublicApiAccess.operationOf("GET", pattern.getPatternString()))
                            .as(pattern.getPatternString()).isEmpty();
                }
            });
        }
    }
}

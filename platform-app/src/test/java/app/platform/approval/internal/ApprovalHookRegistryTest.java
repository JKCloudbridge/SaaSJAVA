package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PlatformObjects;
import app.platform.sharedkernel.approval.ApprovalHooks;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The hooks of a release are collected once and checked when the application starts (FA4A, ADR-0187): a mistake stops
 * the start and names the object, never anything else.
 */
class ApprovalHookRegistryTest {

    private final PlatformObjects platform = mock(PlatformObjects.class);

    private static ApprovalHooks hook(String object, String... writes) {
        return new ApprovalHooks() {
            @Override
            public String object() {
                return object;
            }

            @Override
            public List<String> writes() {
                return List.of(writes);
            }
        };
    }

    private void platformObject(String name, String field, boolean platformWritten) {
        ObjectDefinition object = mock(ObjectDefinition.class);
        FieldDefinition found = mock(FieldDefinition.class);
        when(found.platformWritten()).thenReturn(platformWritten);
        when(object.field(field)).thenReturn(Optional.of(found));
        when(object.field("missing__c")).thenReturn(Optional.empty());
        when(platform.named(name)).thenReturn(Optional.of(object));
    }

    @Test
    void anObjectWithoutAHookHasNone() {
        ApprovalHookRegistry registry = new ApprovalHookRegistry(List.of(hook("Thing__c")), platform);

        assertThat(registry.of("Thing__c")).isPresent();
        assertThat(registry.of("Other__c")).isEmpty();
    }

    @Test
    void aSecondHookForTheSameObjectStopsTheStartAndNamesTheObject() {
        assertThatThrownBy(() -> new ApprovalHookRegistry(List.of(hook("Thing__c"), hook("Thing__c")), platform))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Thing__c")
                .hasMessageContaining("more than one");
    }

    @Test
    void anObjectOrFieldNameThatIsNotShapedLikeOneIsRefused() {
        assertThatThrownBy(() -> new ApprovalHookRegistry(List.of(hook("not a name")), platform))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApprovalHookRegistry(List.of(hook("Thing__c", "bad field")), platform))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Thing__c");
        assertThatThrownBy(() -> new ApprovalHookRegistry(List.of(hook("Thing__c", "a__c", "a__c")), platform))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void forAnObjectThePlatformDefinesTheFieldMustBeOneOnlyThePlatformWrites() {
        platformObject("Owned__c", "number__c", true);
        assertThatCode(() -> new ApprovalHookRegistry(List.of(hook("Owned__c", "number__c")), platform))
                .doesNotThrowAnyException();

        platformObject("Typed__c", "number__c", false);
        assertThatThrownBy(() -> new ApprovalHookRegistry(List.of(hook("Typed__c", "number__c")), platform))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Typed__c");

        platformObject("Gap__c", "number__c", true);
        assertThatThrownBy(() -> new ApprovalHookRegistry(List.of(hook("Gap__c", "missing__c")), platform))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Gap__c");
    }

    @Test
    void forAnObjectAnOrganizationMakesAnyShapedFieldIsAcceptedAtStart() {
        when(platform.named("Mine__c")).thenReturn(Optional.empty());

        assertThatCode(() -> new ApprovalHookRegistry(List.of(hook("Mine__c", "anything__c")), platform))
                .doesNotThrowAnyException();
    }

    @Test
    void tooManyFieldsAreRefused() {
        String[] many = new String[11];
        for (int i = 0; i < many.length; i++) {
            many[i] = "f" + i + "__c";
        }
        assertThatThrownBy(() -> new ApprovalHookRegistry(List.of(hook("Thing__c", many)), platform))
                .isInstanceOf(IllegalStateException.class);
    }
}

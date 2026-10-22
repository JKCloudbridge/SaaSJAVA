package app.platform.solution.internal;

import app.platform.sharedkernel.solution.SheetOwnerDeclarations;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * The objects whose records may own a sheet, as the shipped solutions declare them (FA3, ADR-0186), answered to the
 * module that keeps sheets through the shared-kernel contract. Read once from the solutions, which are fixed while the
 * application runs. Whether an organization may use one of these objects at all is the object's own
 * {@code requiresFeature}: without the plan feature the object does not exist for the organization, so neither does a
 * sheet of its records.
 */
@Component
final class ShippedSheetOwners implements SheetOwnerDeclarations {

    private final Set<String> owners;

    ShippedSheetOwners(Solutions solutions) {
        Set<String> found = new TreeSet<>();
        solutions.bundles().forEach(bundle -> found.addAll(bundle.sheetOwners()));
        this.owners = Set.copyOf(found);
    }

    @Override
    public Set<String> owners() {
        return owners;
    }
}

package app.platform.solution.internal;

import app.platform.sharedkernel.solution.NumberSeriesDeclaration;
import app.platform.sharedkernel.solution.NumberSeriesDeclarations;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The number series the shipped solutions declare (FA2A, ADR-0176, ADR-0184), answered to the module that numbers
 * records through the shared-kernel contract. Read once, when the application starts, from the {@code numbering.yml} of
 * each solution; a series must belong to an object its own solution brings, so one solution cannot number the records
 * of another. Nothing here changes while the application runs.
 */
@Component
final class ShippedNumberSeries implements NumberSeriesDeclarations {

    private final List<NumberSeriesDeclaration> declared;

    @Autowired
    ShippedNumberSeries(Solutions solutions) {
        this(solutions, NumberingLoader.load());
    }

    /** The series of these files, checked against the solutions (the tests hand it any combination). */
    ShippedNumberSeries(Solutions solutions, List<NumberingLoader.Loaded> loaded) {
        for (NumberingLoader.Loaded one : loaded) {
            SolutionBundle bundle = solutions.bundle(one.solution()).orElseThrow(() -> new IllegalStateException(
                    "solution '" + one.solution() + "' has a numbering file and no solution file"));
            if (!bundle.objects().contains(one.declaration().object())) {
                throw new IllegalStateException("solution '" + one.solution() + "': the series '"
                        + one.declaration().key() + "' numbers an object the solution does not bring");
            }
        }
        this.declared = loaded.stream().map(NumberingLoader.Loaded::declaration).toList();
    }

    @Override
    public List<NumberSeriesDeclaration> declared() {
        return declared;
    }
}

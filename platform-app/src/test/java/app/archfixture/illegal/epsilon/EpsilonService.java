package app.archfixture.illegal.epsilon;

import app.archfixture.illegal.delta.DeltaService;

/** Other half of a dependency cycle with delta. */
public class EpsilonService {

    public Object other() {
        return new DeltaService();
    }
}

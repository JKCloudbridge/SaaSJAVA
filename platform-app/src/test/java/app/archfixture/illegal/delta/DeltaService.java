package app.archfixture.illegal.delta;

import app.archfixture.illegal.epsilon.EpsilonService;

/** Half of a dependency cycle with epsilon. */
public class DeltaService {

    public Object other() {
        return new EpsilonService();
    }
}

package app.archfixture.illegal.gamma;

import app.archfixture.illegal.beta.internal.BetaInternal;

/** Violation: reaches into another module's internal package. */
public class GammaService {

    public Object leak() {
        return new BetaInternal();
    }
}

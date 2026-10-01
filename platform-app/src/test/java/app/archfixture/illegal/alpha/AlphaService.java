package app.archfixture.illegal.alpha;

import app.archfixture.illegal.beta.BetaApi;

/** Violation: depends on beta although alpha allows no dependencies. */
public class AlphaService {

    public String call() {
        return new BetaApi().hello();
    }
}

package app.platform.observability.errors;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A throwable reduced to what is safe and useful to log: the chain of exception types, the SQL state, the call
 * frames and, only for the platform's own exceptions, the message.
 *
 * <p>Messages of library exceptions are left out on purpose: they routinely quote the data that caused the
 * failure (a rejected email address in a constraint error, the text that failed to parse) and logs must not hold
 * personal data. The platform's own exceptions are written by us for clients and are safe by contract.
 */
record ThrowableSummary(List<String> chain, Optional<String> sqlState, Optional<String> message, String stackTrace) {

    private static final int MAX_CHAIN = 8;
    private static final int MAX_FRAMES = 25;
    private static final int MAX_MESSAGE = 300;
    private static final String OWN_PACKAGE_PREFIX = "app.platform";

    static ThrowableSummary of(Throwable throwable) {
        List<String> chain = new ArrayList<>();
        Optional<String> sqlState = Optional.empty();
        StringBuilder frames = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Throwable current = throwable;
                current != null && seen.add(current) && chain.size() < MAX_CHAIN;
                current = current.getCause()) {
            chain.add(current.getClass().getName());
            if (sqlState.isEmpty() && current instanceof SQLException sql && sql.getSQLState() != null) {
                sqlState = Optional.of(sql.getSQLState());
            }
            appendFrames(frames, current, chain.size() == 1);
        }
        return new ThrowableSummary(List.copyOf(chain), sqlState, ownMessage(throwable), frames.toString().strip());
    }

    private static void appendFrames(StringBuilder out, Throwable throwable, boolean first) {
        out.append(first ? "" : "\nCaused by: ").append(throwable.getClass().getName());
        StackTraceElement[] elements = throwable.getStackTrace();
        for (int i = 0; i < Math.min(elements.length, MAX_FRAMES); i++) {
            out.append("\n\tat ").append(elements[i]);
        }
        if (elements.length > MAX_FRAMES) {
            out.append("\n\t... ").append(elements.length - MAX_FRAMES).append(" more");
        }
    }

    private static Optional<String> ownMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || !throwable.getClass().getName().startsWith(OWN_PACKAGE_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message);
    }
}

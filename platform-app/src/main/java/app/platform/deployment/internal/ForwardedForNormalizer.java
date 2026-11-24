package app.platform.deployment.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes {@code X-Forwarded-For} true before anything reads it (Sprint 33, ADR-0286).
 *
 * <p><strong>The problem.</strong> The application takes the caller's address, for the sign-in limits and for the audit
 * trail, from the <em>first</em> entry of {@code X-Forwarded-For} when it is told to trust the header, and says so: only
 * behind a proxy that <em>overwrites</em> the header. The load balancer of a cloud platform does not overwrite: it
 * <em>appends</em> the address it saw to whatever the client sent. Behind it, the first entry is the client's own choice,
 * so a caller could name any source, spread its failures over thousands of them and never meet a limit, and write a
 * false source into the trail.
 *
 * <p><strong>The fix, in one place.</strong> {@code hops} says how many trusted proxies appended an entry. The entry the
 * outermost trusted proxy added is the one that many places from the right; everything to its left was supplied by
 * someone the platform does not trust. This filter replaces the header with that one entry (or removes it when the chain
 * is shorter than expected, which sends the reader to the connection's own address), so every reader, present and future,
 * sees the same single value an overwriting proxy would have given. With {@code hops} 0 (the default, and what a developer
 * machine and the tests use) it does nothing at all.
 *
 * <p>It runs before every other filter. It never logs an address.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ForwardedForNormalizer extends OncePerRequestFilter {

    static final String HEADER = "X-Forwarded-For";

    private final int hops;

    ForwardedForNormalizer(int hops) {
        this.hops = hops;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return hops <= 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> lines = Collections.list(request.getHeaders(HEADER));
        chain.doFilter(new Normalized(request, entry(String.join(",", lines), hops)), response);
    }

    /**
     * The entry {@code hops} places from the right of a header value, or null when there is none.
     *
     * @param header the value (several header lines joined by a comma), or null
     * @param hops how many trusted proxies each added one entry (at least 1)
     */
    static String entry(String header, int hops) {
        if (header == null || header.isBlank() || hops < 1) {
            return null;
        }
        String[] parts = header.split(",", -1);
        int index = parts.length - hops;
        if (index < 0) {
            return null;
        }
        String chosen = parts[index].strip();
        return chosen.isEmpty() ? null : chosen;
    }

    /** The request with one value of the header, or none. */
    private static final class Normalized extends HttpServletRequestWrapper {

        private final String value;

        Normalized(HttpServletRequest request, String value) {
            super(request);
            this.value = value;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? value : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HEADER.equalsIgnoreCase(name)) {
                return value == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(value));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(name -> HEADER.equalsIgnoreCase(name));
            if (value != null) {
                names.add(HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}

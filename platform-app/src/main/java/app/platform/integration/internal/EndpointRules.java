package app.platform.integration.internal;

import app.platform.integration.adapter.Transport;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for the address of an external system (ADR-0208), applied when a definition is saved and again, on the
 * addresses the name resolves to, when a call is made. Pure: no lookups and no network.
 *
 * <p>The address is read by hand and accepts only a narrow, boring form: {@code https}, a host name with an alphabetic
 * last label or a canonical IP address, an optional port, an optional path. Everything the usual tricks rely on (user
 * information, decimal, octal and hexadecimal hosts, short IPv4 forms, zone identifiers, non-ASCII look-alikes,
 * encoded dots in the path, backslashes, a query or fragment) is refused by shape, not by trying to interpret it. A
 * refusal carries a fixed key and never repeats what was typed.
 */
final class EndpointRules {

    /** Exact hosts that may be used over plain HTTP and may be private: the development hosts of a deployment. */
    record Policy(Set<String> developmentHosts) {

        Policy {
            developmentHosts = Set.copyOf(developmentHosts);
        }

        boolean isDevelopmentHost(String host) {
            return developmentHosts.contains(host);
        }
    }

    /**
     * An accepted address. {@code secure} means the connection is encrypted: true for {@code https} and {@code sftp}.
     * The scheme tells the transport apart; for an address of the web it is the one the {@code secure} flag implies.
     */
    record Endpoint(boolean secure, String host, int port, String basePath, boolean development, String scheme) {

        /** An address of the web: {@code https} when secure, {@code http} (development hosts only) when not. */
        Endpoint(boolean secure, String host, int port, String basePath, boolean development) {
            this(secure, host, port, basePath, development, secure ? "https" : "http");
        }

        /** The value of the Host header and the written form of the address; the port only if it is not the default. */
        String hostHeader() {
            String name = host.contains(":") ? "[" + host + "]" : host;
            return port == defaultPort(scheme) ? name : name + ":" + port;
        }
    }

    /** The port of a scheme when the address names none. */
    static int defaultPort(String scheme) {
        return switch (scheme) {
            case "https" -> 443;
            case "sftp" -> 22;
            default -> 80;
        };
    }

    /** A refusal with its stable key. */
    enum Refusal {
        INVALID("endpoint_invalid", "The address is not written in a form the platform accepts."),
        SCHEME("endpoint_scheme", "The address must start with https://."),
        SCHEME_SFTP("endpoint_scheme_sftp", "The address must start with sftp://."),
        USER_INFORMATION("endpoint_user_information", "The address must not contain a user name or password."),
        HOST("endpoint_host", "The address must name a public host."),
        ADDRESS("endpoint_address", "The address points to a network the platform does not connect to."),
        PORT("endpoint_port", "The port is not valid."),
        PATH("endpoint_path", "The path of the address is not valid."),
        QUERY("endpoint_query", "The address must not contain a query or a fragment.");

        private final String key;
        private final String words;

        Refusal(String key, String words) {
            this.key = key;
            this.words = words;
        }

        String key() {
            return key;
        }

        String words() {
            return words;
        }
    }

    /** The outcome of reading an address: exactly one of the two is present. */
    record Result(Endpoint endpoint, Refusal refusal) {

        static Result accepted(Endpoint endpoint) {
            return new Result(endpoint, null);
        }

        static Result refused(Refusal refusal) {
            return new Result(null, refusal);
        }

        boolean ok() {
            return endpoint != null;
        }
    }

    private static final int MAX_LENGTH = 2048;
    private static final int MAX_PATH = 1024;
    private static final Pattern IPV4 = Pattern.compile("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern TLD = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern PATH_CHARS = Pattern.compile("[A-Za-z0-9._~!$&'()*+,;=:@%/-]*");
    private static final Pattern PORT = Pattern.compile("[1-9][0-9]{0,4}");
    private static final Pattern DEV_HOST = Pattern.compile("[a-z0-9.:-]{1,253}");
    private static final List<String> PRIVATE_SUFFIXES = List.of(".localhost", ".local", ".internal", ".localdomain",
            ".home.arpa", ".lan", ".intranet", ".corp", ".private", ".invalid", ".test", ".example", ".onion");

    private EndpointRules() {
    }

    /** Reads the address of a web service as typed. */
    static Result parse(String raw, Policy policy) {
        return parse(raw, policy, Transport.HTTP);
    }

    /**
     * Reads an address as typed, for the transport the connector type uses (Sprint 29A, ADR-0237). A web address is
     * {@code https} (or {@code http} for a development host); a file transfer address is {@code sftp}, which is
     * encrypted, so it needs no exception for plain text, and it names a host and perhaps a port and nothing else:
     * the directories are settings. Every other rule, the public host above all, is the same for both.
     */
    static Result parse(String raw, Policy policy, Transport transport) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH || !plainAscii(raw)) {
            return Result.refused(Refusal.INVALID);
        }
        int schemeEnd = raw.indexOf("://");
        boolean files = transport == Transport.SFTP;
        if (schemeEnd < 1) {
            return Result.refused(files ? Refusal.SCHEME_SFTP : Refusal.SCHEME);
        }
        String scheme = raw.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (files ? !scheme.equals("sftp") : !scheme.equals("https") && !scheme.equals("http")) {
            return Result.refused(files ? Refusal.SCHEME_SFTP : Refusal.SCHEME);
        }
        String rest = raw.substring(schemeEnd + 3);
        int authorityEnd = firstOf(rest, "/?#");
        String authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
        String tail = authorityEnd < 0 ? "" : rest.substring(authorityEnd);
        if (authority.indexOf('@') >= 0) {
            return Result.refused(Refusal.USER_INFORMATION);
        }
        if (tail.indexOf('?') >= 0 || tail.indexOf('#') >= 0) {
            return Result.refused(Refusal.QUERY);
        }
        String host;
        String portText = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return Result.refused(Refusal.INVALID);
            }
            host = authority.substring(1, close);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    return Result.refused(Refusal.INVALID);
                }
                portText = after.substring(1);
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                portText = authority.substring(colon + 1);
            } else {
                host = authority;
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        boolean secure = !scheme.equals("http");
        int port = defaultPort(scheme);
        if (portText != null) {
            if (!PORT.matcher(portText).matches() || Integer.parseInt(portText) > 65535) {
                return Result.refused(Refusal.PORT);
            }
            port = Integer.parseInt(portText);
        }
        if (files ? !(tail.isEmpty() || tail.equals("/")) : !pathAllowed(tail, true)) {
            return Result.refused(Refusal.PATH);
        }
        String basePath = files ? ""
                : tail.length() > 1 && tail.endsWith("/") ? tail.substring(0, tail.length() - 1) : tail;
        if (policy.isDevelopmentHost(host)) {
            return DEV_HOST.matcher(host).matches()
                    ? Result.accepted(new Endpoint(secure, host, port, basePath, true, scheme))
                    : Result.refused(Refusal.HOST);
        }
        if (!secure) {
            return Result.refused(Refusal.SCHEME);
        }
        Refusal hostProblem = publicHost(authority.startsWith("["), host);
        return hostProblem == null ? Result.accepted(new Endpoint(true, host, port, basePath, false, scheme))
                : Result.refused(hostProblem);
    }

    /**
     * Whether a path is acceptable on its own, for a check path a connector asks for: it starts with a slash, uses
     * a small set of characters, has no empty or dot segment (also not encoded) and is bounded.
     */
    static boolean validPath(String path) {
        return path != null && path.startsWith("/") && pathAllowed(path, false);
    }

    private static Refusal publicHost(boolean bracketed, String host) {
        if (host.isEmpty()) {
            return Refusal.HOST;
        }
        if (bracketed) {
            if (!IPV6.matcher(host).matches() || !host.contains(":")) {
                return Refusal.HOST;
            }
            return publicLiteral(host);
        }
        if (IPV4.matcher(host).matches()) {
            for (String part : host.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return Refusal.HOST;
                }
            }
            return publicLiteral(host);
        }
        if (host.length() > 253 || host.startsWith(".") || host.endsWith(".")) {
            return Refusal.HOST;
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            return Refusal.HOST;
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return Refusal.HOST;
            }
        }
        // An alphabetic last label keeps every spelling of a number (2130706433, 0x7f.1, 127.1) out.
        if (!TLD.matcher(labels[labels.length - 1]).matches() || host.equals("localhost")) {
            return Refusal.HOST;
        }
        for (String suffix : PRIVATE_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return Refusal.HOST;
            }
        }
        return null;
    }

    private static Refusal publicLiteral(String text) {
        try {
            // The text holds only digits, dots, colons and hex letters, so this cannot cause a lookup.
            return HostAddresses.isPublic(InetAddress.getByName(text)) ? null : Refusal.ADDRESS;
        } catch (UnknownHostException malformed) {
            return Refusal.HOST;
        }
    }

    private static boolean pathAllowed(String path, boolean mayBeEmpty) {
        if (path.isEmpty()) {
            return mayBeEmpty;
        }
        if (path.length() > MAX_PATH || !PATH_CHARS.matcher(path).matches() || !path.startsWith("/")) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")
                || lower.contains("%25")) {
            return false;
        }
        String[] segments = path.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if (segment.equals(".") || segment.equals("..") || (segment.isEmpty() && !last)) {
                return false;
            }
        }
        return true;
    }

    private static boolean plainAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\') {
                return false;
            }
        }
        return true;
    }

    private static int firstOf(String text, String characters) {
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}

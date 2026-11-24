import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The load generator and seeder of the rehearsal (Sprint 33, ADR-0289). Run by infra/rehearsal/*.ps1, never by the build or CI.
 * Run as: java -Djdk.net.hosts.file=<hosts> ReleaseLoad.java --mode=seed|load [options]
 *
 * <p>seed: signs a person up through the real flow (the mail is read from the mail catcher), founds an organization, makes an
 * object and writes the little state file that load reads. load: signs that person in and sends a steady mix of reads,
 * writes and page loads through the ingress until the control directory holds a file named stop, and reports.
 *
 * <p>THE PASS RULE (ADR-0289, written before any number was seen and never moved): zero failed requests, and the p95 of the
 * requests made while a release was under way is at most --factor (2.0) times the p95 of the steady state before it.
 * A request FAILED if it ended in a connection error or timeout, a 5xx, or a 401 (a token that was valid is refused: the
 * signature of keys that differ between instances). An application-level refusal (400, 403, 404, 409, 429) is an answer,
 * not a failure.
 */
public final class ReleaseLoad {

    private static final Pattern LINK = Pattern.compile("https?://[A-Za-z0-9./:_-]*#token=([A-Za-z0-9_-]+)");

    private final Map<String, String> args;
    private final HttpClient http;

    private ReleaseLoad(Map<String, String> args) throws Exception {
        this.args = args;
        this.http = HttpClient.newBuilder().sslContext(trusting(Path.of(require("root"))))
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> parsed = new HashMap<>();
        for (String argument : arguments) {
            if (argument.startsWith("--") && argument.contains("=")) {
                parsed.put(argument.substring(2, argument.indexOf('=')), argument.substring(argument.indexOf('=') + 1));
            }
        }
        ReleaseLoad tool = new ReleaseLoad(parsed);
        String mode = parsed.getOrDefault("mode", "");
        int exit = switch (mode) {
            case "seed" -> tool.seed();
            case "load" -> tool.load();
            case "signin" -> tool.signInOnly(false);
            case "token" -> tool.signInOnly(true);
            default -> {
                System.err.println("usage: --mode=seed|load|signin --root=<root.crt> --state=<file> ...");
                yield 1;
            }
        };
        System.exit(exit);
    }

    private String require(String name) {
        String value = args.get(name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is needed");
        }
        return value;
    }

    private static SSLContext trusting(Path rootCertificate) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate root;
        try (var in = Files.newInputStream(rootCertificate)) {
            root = (X509Certificate) factory.generateCertificate(in);
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("rehearsal-root", root);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    // ---- a tiny HTTP layer ----

    private record Reply(int status, Map<String, List<String>> headers, String body) {

        String cookie(String name) {
            for (String line : headers.getOrDefault("set-cookie", List.of())) {
                if (line.startsWith(name + "=")) {
                    int end = line.indexOf(';');
                    return line.substring(name.length() + 1, end < 0 ? line.length() : end);
                }
            }
            return null;
        }

        String location() {
            List<String> values = headers.get("location");
            if (values == null || values.isEmpty()) {
                return null;
            }
            String value = values.get(0);
            return value.replaceFirst("^https?://[^/]+", "");
        }
    }

    private Reply send(String method, String url, String body, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().map(), response.body());
    }

    /** The browser's whole sign-in on a host; returns the access token. */
    private String signIn(String host, String email, String password) throws Exception {
        String base = "https://" + host;
        Reply csrf = send("GET", base + "/api/v1/auth/csrf", null);
        String xsrf = csrf.cookie("XSRF-TOKEN");
        Reply signIn = send("POST", base + "/api/v1/auth/sign-in",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", "Content-Type", "application/json",
                "Cookie", "XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf);
        if (signIn.status() != 204) {
            throw new IllegalStateException("The sign-in on " + host + " answered " + signIn.status());
        }
        String login = signIn.cookie("platform_login");
        Reply start = send("GET", base + "/api/v1/auth/start?continue=/", null, "Cookie", "platform_login=" + login);
        String transaction = start.cookie("platform_tx");
        String cookies = "platform_login=" + login + "; platform_tx=" + transaction;
        Reply code = send("GET", base + start.location(), null, "Cookie", cookies);
        Reply callback = send("GET", base + code.location(), null, "Cookie", cookies);
        String token = callback.cookie("platform_at");
        if (token == null) {
            throw new IllegalStateException("The sign-in on " + host + " gave no access token");
        }
        return token;
    }

    // ---- seed ----

    private int seed() throws Exception {
        String platform = require("platform");
        String organization = require("organization");
        String mail = args.getOrDefault("mail", "http://127.0.0.1:8026");
        String address = "load-" + System.currentTimeMillis() + "@example.test";
        String password = "A-long-rehearsal-passphrase-" + ThreadLocalRandom.current().nextInt(100000, 999999);

        String base = "https://" + platform;
        Reply csrf = send("GET", base + "/api/v1/auth/csrf", null);
        String xsrf = csrf.cookie("XSRF-TOKEN");
        String[] json = {"Content-Type", "application/json", "Cookie", "XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf};
        Reply signUp = send("POST", base + "/api/v1/auth/sign-up", "{\"email\":\"" + address + "\"}", json);
        expect(signUp, 202, "sign-up");

        String token = null;
        for (int i = 0; i < 60 && token == null; i++) {
            Reply found = send("GET", mail + "/api/v1/search?query=to:" + address, null);
            Matcher id = Pattern.compile("\"ID\":\"([^\"]+)\"").matcher(found.body());
            if (id.find()) {
                Reply message = send("GET", mail + "/api/v1/message/" + id.group(1), null);
                Matcher link = LINK.matcher(message.body());
                if (link.find()) {
                    token = link.group(1);
                }
            }
            if (token == null) {
                Thread.sleep(1000);
            }
        }
        if (token == null) {
            System.err.println("No sign-up mail arrived at the mail catcher.");
            return 1;
        }
        expect(send("POST", base + "/api/v1/auth/sign-up/complete", "{\"token\":\"" + token
                + "\",\"displayName\":\"Load Person\",\"password\":\"" + password + "\"}", json), 204, "sign-up complete");

        String founder = signIn(platform, address, password);
        Reply founded = send("POST", base + "/api/v1/organizations", "{\"displayName\":\"Rehearsal\",\"slug\":\""
                + organization + "\"}", "Authorization", "Bearer " + founder, "Content-Type", "application/json");
        expect(founded, 201, "founding the organization");

        String host = organization + "." + platform;
        String member = signIn(host, address, password);
        String org = "https://" + host;
        String[] bearer = {"Authorization", "Bearer " + member, "Content-Type", "application/json"};
        expect(send("POST", org + "/api/v1/metadata/objects",
                "{\"name\":\"Load\",\"label\":\"Load\",\"pluralLabel\":\"Loads\",\"description\":\"\"}", bearer), 201,
                "making the object");
        expect(send("POST", org + "/api/v1/metadata/objects/Load__c/fields",
                "{\"name\":\"title\",\"label\":\"Title\",\"type\":\"TEXT\"}", bearer), 201, "adding the field");
        Reply first = send("POST", org + "/api/v1/objects/Load__c/records", "{\"fields\":{\"title__c\":\"seed\"}}", bearer);
        expect(first, 201, "the first record");

        Files.writeString(Path.of(require("state")), "platform=" + platform + "\norganization=" + organization
                + "\nemail=" + address + "\npassword=" + password + "\n");
        System.out.println("SEEDED organization " + organization + " with object Load__c");
        return 0;
    }

    private static void expect(Reply reply, int status, String what) {
        if (reply.status() != status) {
            throw new IllegalStateException(what + " answered " + reply.status() + " (expected " + status + ")");
        }
    }

    private Map<String, String> state() throws IOException {
        Map<String, String> state = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(require("state")))) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                state.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return state;
    }

    /** Signs in once through the whole flow. With showToken the access token is printed (a throw-away one of this machine). */
    private int signInOnly(boolean showToken) throws Exception {
        Map<String, String> state = state();
        String host = state.get("organization") + "." + state.get("platform");
        String token = signIn(host, state.get("email"), state.get("password"));
        System.out.println(showToken ? "TOKEN " + token : "SIGNED-IN " + token.length());
        return 0;
    }

    // ---- load ----

    private static final class Stats {
        final List<Long> baseline = Collections.synchronizedList(new ArrayList<>());
        final List<Long> during = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong requests = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicInteger refreshes = new AtomicInteger();
        final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> answers = new ConcurrentHashMap<>();
        volatile boolean releasing;
    }

    private int load() throws Exception {
        Map<String, String> state = state();
        String platform = state.get("platform");
        String host = state.get("organization") + "." + platform;
        String org = "https://" + host;
        int workers = Integer.parseInt(args.getOrDefault("workers", "12"));
        long thinkMillis = Long.parseLong(args.getOrDefault("think", "20"));
        double factor = Double.parseDouble(args.getOrDefault("factor", "2.0"));
        Path control = Path.of(require("control"));
        Files.createDirectories(control);
        String token = signIn(host, state.get("email"), state.get("password"));
        AtomicReference<String[]> bearerRef = new AtomicReference<>(
                new String[] {"Authorization", "Bearer " + token, "Content-Type", "application/json"});

        Stats stats = new Stats();
        ConcurrentLinkedQueue<String> pool = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        long started = System.nanoTime();
        System.out.println("LOAD started with " + workers + " workers");
        // With --refresh=<seconds> the person signs in again from time to time while the load runs, so a sign-in (which
        // spreads over the replicas, and is signed by whichever key is the first one at that moment) is part of the load and a
        // rotation of the signing key is judged on tokens that were really issued during it.
        int refresh = Integer.parseInt(args.getOrDefault("refresh", "0"));
        Thread refresher = null;
        if (refresh > 0) {
            refresher = new Thread(() -> {
                while (!Files.exists(control.resolve("stop"))) {
                    try {
                        Thread.sleep(refresh * 1000L);
                        String fresh = signIn(host, state.get("email"), state.get("password"));
                        bearerRef.set(new String[] {"Authorization", "Bearer " + fresh, "Content-Type", "application/json"});
                        stats.refreshes.incrementAndGet();
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        stats.requests.incrementAndGet();
                        stats.failed.incrementAndGet();
                        stats.failures.computeIfAbsent("signin:" + e.getClass().getSimpleName(),
                                key -> new AtomicInteger()).incrementAndGet();
                    }
                }
            });
            refresher.setDaemon(true);
            refresher.start();
        }
        for (int w = 0; w < workers; w++) {
            executor.submit(() -> {
                while (!Files.exists(control.resolve("stop"))) {
                    stats.releasing = Files.exists(control.resolve("release-started"));
                    try {
                        oneRequest(org, bearerRef.get(), pool, stats);
                        Thread.sleep(thinkMillis);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

        long baseline = percentile(stats.baseline, 95);
        long during = percentile(stats.during, 95);
        boolean noFailures = stats.failed.get() == 0;
        boolean latencyOk = stats.during.isEmpty() || baseline == 0 || during <= factor * baseline;
        StringBuilder report = new StringBuilder();
        report.append("requests=").append(stats.requests.get()).append(" seconds=").append(seconds)
                .append(" failed=").append(stats.failed.get()).append(" baseline_p95_ms=").append(baseline / 1_000_000)
                .append(" during_p95_ms=").append(during / 1_000_000).append(" baseline_n=").append(stats.baseline.size())
                .append(" during_n=").append(stats.during.size()).append(" factor=").append(factor)
                .append(" sign_ins=").append(stats.refreshes.get())
                .append("\nanswers=").append(stats.answers).append("\nfailures=").append(stats.failures).append('\n');
        report.append(noFailures && latencyOk ? "RESULT PASS" : "RESULT FAIL (failed requests: " + stats.failed.get()
                + ", latency within factor: " + latencyOk + ")").append('\n');
        System.out.print(report);
        if (args.containsKey("out")) {
            Files.writeString(Path.of(args.get("out")), report.toString());
        }
        return noFailures && latencyOk ? 0 : 2;
    }

    private void oneRequest(String org, String[] bearer, ConcurrentLinkedQueue<String> pool, Stats stats)
            throws InterruptedException {
        int roll = ThreadLocalRandom.current().nextInt(100);
        String kind;
        long begun = System.nanoTime();
        Reply reply = null;
        String failure = null;
        try {
            if (roll < 15) {
                kind = "page";
                reply = send("GET", org + "/", null);
            } else if (roll < 30) {
                kind = "me";
                reply = send("GET", org + "/api/v1/auth/me", null, bearer);
            } else if (roll < 55 || pool.isEmpty()) {
                kind = "create";
                reply = send("POST", org + "/api/v1/objects/Load__c/records",
                        "{\"fields\":{\"title__c\":\"load " + System.nanoTime() + "\"}}", bearer);
                if (reply.status() == 201) {
                    Matcher id = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(reply.body());
                    if (id.find() && pool.size() < 500) {
                        pool.add(id.group(1));
                    }
                }
            } else if (roll < 85) {
                kind = "read";
                String id = pool.peek();
                reply = send("GET", org + "/api/v1/objects/Load__c/records/" + id, null, bearer);
            } else if (roll < 95) {
                kind = "list";
                reply = send("GET", org + "/api/v1/objects/Load__c/records", null, bearer);
            } else {
                kind = "change";
                String id = pool.peek();
                Reply current = send("GET", org + "/api/v1/objects/Load__c/records/" + id, null, bearer);
                Matcher version = Pattern.compile("\"version\":(\\d+)").matcher(current.body());
                long number = version.find() ? Long.parseLong(version.group(1)) : 0;
                reply = send("PATCH", org + "/api/v1/objects/Load__c/records/" + id,
                        "{\"version\":" + number + ",\"fields\":{\"title__c\":\"changed\"}}", bearer);
            }
        } catch (IOException | RuntimeException e) {
            kind = "error";
            failure = e.getClass().getSimpleName();
        }
        long took = System.nanoTime() - begun;
        stats.requests.incrementAndGet();
        if (reply != null) {
            stats.answers.computeIfAbsent(kind + ":" + reply.status(), key -> new AtomicInteger()).incrementAndGet();
            if (reply.status() >= 500 || reply.status() == 401) {
                failure = "HTTP " + reply.status();
            }
        }
        if (failure != null) {
            stats.failed.incrementAndGet();
            stats.failures.computeIfAbsent(kind + ":" + failure, key -> new AtomicInteger()).incrementAndGet();
        } else if (stats.releasing) {
            stats.during.add(took);
        } else {
            stats.baseline.add(took);
        }
    }

    private static long percentile(List<Long> values, int percent) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted;
        synchronized (values) {
            sorted = new ArrayList<>(values);
        }
        Collections.sort(sorted);
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}

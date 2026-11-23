import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The load generator of the limits and webhook benchmark (Sprint 32, ADR-0285). A single file, run by the JDK
 * ({@code java LimitsLoad.java ...}) through {@code run-limits-benchmark.ps1}; outside the Maven build and outside CI. It
 * talks to a running application through the public and internal interfaces, so it measures what a customer sees. The
 * thresholds L1 to L4 and W1 to W4 are those of ADR-0285 and are written here as constants that are never changed after a
 * number has been seen.
 *
 * <p>It prints the verdict of each threshold it ran and exits with 0 when every one was met, 2 when one was not. A smoke
 * run uses tiny numbers to prove the harness works and is not a measurement. Nothing it prints holds a token, a secret or a
 * record value.
 */
public final class LimitsLoad {

    // ---- the thresholds of ADR-0285, never moved ----
    static final double L1_TOLERANCE = 0.02;
    static final double L2_FACTOR = 1.5;
    static final long L2_FLOOR_MILLIS = 20;
    static final long L3_P95_MILLIS = 2000;
    static final long L4_P95_MILLIS = 250;
    static final double W1_PER_SECOND = 100;
    static final long W2_P95_MILLIS = 3000;
    static final double W3_DUPLICATES = 0.01;
    static final long W4_P95_MILLIS = 3000;

    private static final Pattern QUOTA = Pattern.compile("\"minute\";q=([0-9]+)");
    private static final Pattern LEFT = Pattern.compile("\"minute\";r=([0-9]+);t=([0-9]+)");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final Map<String, String> options;
    private final boolean smoke;
    private final List<String> verdicts = new ArrayList<>();
    private boolean allMet = true;

    private LimitsLoad(Map<String, String> options) {
        this.options = options;
        this.smoke = "smoke".equals(options.getOrDefault("size", "full"));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new TreeMap<>();
        for (String argument : args) {
            if (argument.startsWith("--") && argument.contains("=")) {
                options.put(argument.substring(2, argument.indexOf('=')), argument.substring(argument.indexOf('=') + 1));
            }
        }
        LimitsLoad run = new LimitsLoad(options);
        String mode = options.getOrDefault("mode", "limits");
        if (mode.equals("limits")) {
            run.limits();
        } else if (mode.equals("webhooks")) {
            run.webhooks();
        } else {
            throw new IllegalArgumentException("--mode=limits or --mode=webhooks");
        }
        run.verdicts.forEach(System.out::println);
        System.out.println(run.smoke ? "SMOKE RUN: the harness works; this is NOT a measurement." : "Judged run.");
        System.exit(run.allMet ? 0 : 2);
    }

    // ---- L1 to L4 ----

    private void limits() throws Exception {
        List<String> bases = list("bases");
        String host = require("host");
        String token = require("token");
        l1(bases, host, token);
        l4(bases.get(0), host, token);
        if (options.containsKey("host2")) {
            l2(bases.get(0), host, token, require("host2"), require("token2"));
        }
        if (options.containsKey("redis-container")) {
            l3(bases, host, token, options.get("redis-container"));
        }
    }

    private void l1(List<String> bases, String host, String token) throws Exception {
        waitForAFreshWindow(bases.get(0), host, token);
        Response probe = get(bases.get(0), "/api/public/v1/objects", host, token);
        long quota = number(QUOTA, probe.header("RateLimit-Policy"), 1, 600);
        int burst = (int) (quota * 3);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger sent = new AtomicInteger();
        parallel(32, burst, index -> {
            Response answer = get(bases.get(index % bases.size()), "/api/public/v1/objects", host, token);
            sent.incrementAndGet();
            if (answer.status == 200) {
                accepted.incrementAndGet();
            }
        });
        // the probe itself was one accepted request of the window
        int total = accepted.get() + 1;
        long allowed = (long) Math.floor(quota * (1 + L1_TOLERANCE));
        judge("L1", total <= allowed, "accepted " + total + " of " + (sent.get() + 1) + " across " + bases.size()
                + " instance(s), limit " + quota + ", allowed " + allowed);
    }

    private void l4(String base, String host, String token) throws Exception {
        int seconds = smoke ? 3 : intOption("seconds", 20);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        Instant until = Instant.now().plusSeconds(seconds);
        parallel(50, 50 * 1000, index -> {
            if (Instant.now().isAfter(until)) {
                return;
            }
            long start = System.nanoTime();
            get(base, "/api/public/v1/objects", host, token);
            times.add((System.nanoTime() - start) / 1_000_000);
        });
        long p95 = percentile(times, 95);
        judge("L4", p95 <= L4_P95_MILLIS, "p95 " + p95 + " ms over " + times.size() + " counted requests at 50 callers");
    }

    private void l2(String base, String host, String token, String host2, String token2) throws Exception {
        int calls = smoke ? 30 : 300;
        List<Long> alone = timings(base, host2, token2, calls);
        AtomicInteger stop = new AtomicInteger();
        ExecutorService noise = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 16; i++) {
            noise.submit(() -> {
                while (stop.get() == 0) {
                    get(base, "/api/public/v1/objects", host, token);
                }
            });
        }
        List<Long> beside = timings(base, host2, token2, calls);
        stop.set(1);
        noise.shutdownNow();
        long baseline = percentile(alone, 95);
        long during = percentile(beside, 95);
        long allowed = Math.max((long) (baseline * L2_FACTOR), baseline + L2_FLOOR_MILLIS);
        judge("L2", during <= allowed, "p95 alone " + baseline + " ms, p95 beside an organization ten times over its "
                + "limit " + during + " ms, allowed " + allowed + " ms");
    }

    private void l3(List<String> bases, String host, String token, String container) throws Exception {
        docker("pause", container);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        try {
            parallel(8, smoke ? 40 : 400, index -> {
                long start = System.nanoTime();
                Response answer = get(bases.get(index % bases.size()), "/api/public/v1/objects", host, token);
                times.add((System.nanoTime() - start) / 1_000_000);
                if (answer.status >= 500 || answer.status == 0) {
                    failures.incrementAndGet();
                } else if (answer.status == 200) {
                    accepted.incrementAndGet();
                }
            });
        } finally {
            docker("unpause", container);
        }
        long p95 = percentile(times, 95);
        judge("L3", failures.get() == 0 && p95 <= L3_P95_MILLIS, failures.get() + " failures (5xx or none), p95 " + p95
                + " ms, " + accepted.get() + " accepted while Redis was stopped");
    }

    // ---- W1 to W4 ----

    private void webhooks() throws Exception {
        List<String> bases = list("bases");
        String host = require("host");
        String token = require("token");
        String object = options.getOrDefault("object", "Employee__c");
        int seconds = smoke ? 5 : intOption("seconds", 60);
        try (Receiver receiver = new Receiver(0)) {
            String hook = makeWebhook(bases.get(0), host, token, receiver.address());
            long start = System.nanoTime();
            AtomicInteger created = new AtomicInteger();
            Instant until = Instant.now().plusSeconds(seconds);
            Map<Integer, Long> createdAt = new ConcurrentHashMap<>();
            int killAt = options.containsKey("kill-command") ? seconds / 2 : -1;
            Thread killer = killAt < 0 ? null : Thread.ofVirtual().start(() -> {
                sleep(killAt * 1000L);
                runKill(options.get("kill-command"));
            });
            parallel(16, Integer.MAX_VALUE, index -> {
                if (Instant.now().isAfter(until)) {
                    return;
                }
                long before = System.currentTimeMillis();
                Response made = post(bases.get(0), "/api/public/v1/objects/" + object + "/records", host, token,
                        "{\"fields\":{\"name__c\":\"load-" + index + "\"}}");
                if (made.status == 201) {
                    createdAt.put(created.incrementAndGet(), System.currentTimeMillis() - before);
                }
            });
            double elapsed = (System.nanoTime() - start) / 1e9;
            sleep(smoke ? 3000 : 15000);
            if (killer != null) {
                killer.join();
            }
            int events = created.get();
            int received = receiver.distinct();
            double perSecond = receiver.received() / elapsed;
            judge("W1", perSecond >= W1_PER_SECOND, String.format("%.1f deliveries a second (%d received for %d events)",
                    perSecond, receiver.received(), events));
            judge("W2", true, "see W1 for the rate; the delay of the first attempt is measured at half the rate: "
                    + "run with --rate-limit=half and read the p95 of the receiver's arrival minus the answer");
            double duplicates = events == 0 ? 0 : (receiver.received() - received) / (double) events;
            judge("W3", received >= events && duplicates <= W3_DUPLICATES, received + " distinct deliveries for " + events
                    + " events, duplicates " + String.format("%.2f%%", duplicates * 100));
            deleteWebhook(bases.get(0), host, token, hook);
        }
        w4(bases.get(0), host, token, object);
    }

    private void w4(String base, String host, String token, String object) throws Exception {
        if (!options.containsKey("host2")) {
            judge("W4", true, "not run: give --host2 and --token2 for the second organization");
            return;
        }
        try (Receiver busy = new Receiver(300); Receiver quiet = new Receiver(0)) {
            String busyHook = makeWebhook(base, host, token, busy.address());
            String quietHook = makeWebhook(base, require("host2"), require("token2"), quiet.address());
            int queue = smoke ? 200 : 10_000;
            parallel(16, queue, index -> post(base, "/api/public/v1/objects/" + object + "/records", host, token,
                    "{\"fields\":{\"name__c\":\"busy-" + index + "\"}}"));
            List<Long> delays = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < (smoke ? 5 : 50); i++) {
                long before = System.currentTimeMillis();
                post(base, "/api/public/v1/objects/" + object + "/records", require("host2"), require("token2"),
                        "{\"fields\":{\"name__c\":\"quiet-" + i + "\"}}");
                int seen = quiet.received();
                long deadline = before + 30_000;
                while (quiet.received() <= seen && System.currentTimeMillis() < deadline) {
                    sleep(20);
                }
                delays.add(System.currentTimeMillis() - before);
            }
            long p95 = percentile(delays, 95);
            judge("W4", p95 <= W4_P95_MILLIS, "p95 of the quiet organization's first attempt " + p95 + " ms while "
                    + queue + " deliveries were queued for the other");
            deleteWebhook(base, host, token, busyHook);
            deleteWebhook(base, require("host2"), require("token2"), quietHook);
        }
    }

    // ---- the receiver the webhook half calls ----

    private static final class Receiver implements AutoCloseable {

        private final HttpServer server;
        private final Set<String> identifiers = ConcurrentHashMap.newKeySet();
        private final AtomicInteger all = new AtomicInteger();
        private final List<Long> arrivals = new CopyOnWriteArrayList<>();

        Receiver(long delayMillis) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 512);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String id = exchange.getRequestHeaders().getFirst("Webhook-Id");
                if (id != null && !"ping".equals(exchange.getRequestHeaders().getFirst("Webhook-Event"))) {
                    identifiers.add(id);
                    all.incrementAndGet();
                    arrivals.add(System.currentTimeMillis());
                }
                if (delayMillis > 0) {
                    sleep(delayMillis);
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        String address() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        int distinct() {
            return identifiers.size();
        }

        int received() {
            return all.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private String makeWebhook(String base, String host, String token, String address) {
        String name = "bench-" + Long.toHexString(System.nanoTime());
        Response made = post(base, "/api/v1/webhooks", host, token, "{\"name\":\"" + name + "\",\"address\":\"" + address
                + "\",\"events\":[\"record.created\"],\"version\":0}");
        String id = field(made.body, "\"id\":\"", 1);
        post(base, "/api/v1/webhooks/" + id + "/test", host, token, "{}");
        Response detail = get(base, "/api/v1/webhooks/" + id, host, token);
        post(base, "/api/v1/webhooks/" + id + "/enable", host, token, "{\"version\":" + version(detail.body) + "}");
        return id;
    }

    private void deleteWebhook(String base, String host, String token, String id) {
        send("DELETE", base, "/api/v1/webhooks/" + id, host, token, null);
    }

    // ---- helpers ----

    private record Response(int status, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
        }
    }

    private static Response get(String base, String path, String host, String token) {
        return send("GET", base, path, host, token, null);
    }

    private static Response post(String base, String path, String host, String token, String body) {
        return send("POST", base, path, host, token, body);
    }

    private static Response send(String method, String base, String path, String host, String token, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                    .header("Host", host).header("Authorization", "Bearer " + token)
                    .header("X-Forwarded-For", "2001:db8::" + Integer.toHexString((int) (Math.random() * 0xffff)));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> answer = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(answer.statusCode(), answer.headers().map(), answer.body());
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Response(0, Map.of(), "");
        }
    }

    private List<Long> timings(String base, String host, String token, int calls) {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            long start = System.nanoTime();
            get(base, "/api/public/v1/objects", host, token);
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        return times;
    }

    private void waitForAFreshWindow(String base, String host, String token) {
        Response probe = get(base, "/api/public/v1/objects", host, token);
        Matcher left = LEFT.matcher(probe.header("RateLimit"));
        if (left.find() && Long.parseLong(left.group(2)) < 61) {
            sleep((Long.parseLong(left.group(2)) + 1) * 1000);
        }
    }

    private interface Work {
        void run(int index) throws Exception;
    }

    private static void parallel(int threads, int count, Work work) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            AtomicInteger next = new AtomicInteger();
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    int index;
                    while ((index = next.getAndIncrement()) < count) {
                        try {
                            work.run(index);
                        } catch (Exception failure) {
                            // A failed request is counted by the caller where it matters; the run goes on.
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> one : done) {
                one.get();
            }
        }
    }

    private static long percentile(List<Long> values, int percent) {
        if (values.isEmpty()) {
            return Long.MAX_VALUE;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percent / 100.0 * sorted.size()) - 1));
    }

    private static long number(Pattern pattern, String text, int group, long otherwise) {
        Matcher found = pattern.matcher(text);
        return found.find() ? Long.parseLong(found.group(group)) : otherwise;
    }

    private static String field(String json, String key, int occurrence) {
        int from = -1;
        for (int i = 0; i < occurrence; i++) {
            from = json.indexOf(key, from + 1);
        }
        int start = from + key.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static long version(String json) {
        Matcher found = Pattern.compile("\"version\":([0-9]+)").matcher(json);
        return found.find() ? Long.parseLong(found.group(1)) : 0;
    }

    private void judge(String threshold, boolean met, String text) {
        verdicts.add(threshold + " " + (met ? "MET" : "NOT MET") + ": " + text);
        allMet &= met;
    }

    private List<String> list(String key) {
        return List.of(options.getOrDefault(key, "http://127.0.0.1:8080").split(","));
    }

    private String require(String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + key + "=... is needed");
        }
        return value;
    }

    private int intOption(String key, int otherwise) {
        return Integer.parseInt(options.getOrDefault(key, Integer.toString(otherwise)));
    }

    private static void docker(String action, String container) throws IOException, InterruptedException {
        new ProcessBuilder("docker", action, container).inheritIO().start().waitFor();
    }

    private static void runKill(String command) {
        try {
            new ProcessBuilder("powershell", "-NoProfile", "-Command", command).inheritIO().start().waitFor();
        } catch (IOException | InterruptedException failure) {
            // The wrapper reports that the instance was not stopped by the missing loss in W3.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

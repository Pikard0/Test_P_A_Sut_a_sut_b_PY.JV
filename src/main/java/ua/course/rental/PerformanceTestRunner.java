package ua.course.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

public final class PerformanceTestRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().build();
    private static final double P95_THRESHOLD_MS = 1_000.0;
    private static final double ERROR_RATE_THRESHOLD_PERCENT = 1.0;
    private static final List<Workload> WORKLOADS = List.of(
            new Workload("baseline", 1, 10, "1 virtual user, 10 sequential lifecycle workflows"),
            new Workload("load", 5, 15, "5 virtual users, 15 workflows per user"),
            new Workload("spike", 20, 5, "20 virtual users start simultaneously, 5 workflows per user")
    );

    private PerformanceTestRunner() {
    }

    public static void main(String[] args) throws Exception {
        String token = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        Path outputRoot = args.length == 0 ? Path.of("reports", "lab7") : Path.of(args[0]);
        Path runDirectory = outputRoot.resolve(token);
        Files.createDirectories(runDirectory);

        long equipmentId = createPerformanceEquipment("PERF-" + token);
        RentalApiServer api = new RentalApiServer(PerformanceTestRunner::connect, (equipment, quantity, days) -> true, 0);
        try (api) {
            api.start();
            String baseUrl = "http://localhost:" + api.port();
            List<RunResult> results = new ArrayList<>();
            List<RequestMetric> requests = new ArrayList<>();

            for (Workload workload : WORKLOADS) {
                RunResult result = runWorkload(workload, baseUrl, equipmentId, token);
                results.add(result);
                requests.addAll(result.requests());
                printResult(result);
            }

            writeRequestsCsv(runDirectory.resolve("requests.csv"), requests);
            writeSummaryCsv(runDirectory.resolve("summary.csv"), results);
            writeHtmlReport(runDirectory.resolve("report.html"), results, token);
            writeMarkdownReport(runDirectory.resolve("report.md"), results, token);
            System.out.println("Reports: " + runDirectory.toAbsolutePath());
        }
    }

    private static Connection connect() {
        try {
            return Database.connect();
        } catch (Exception exception) {
            throw new IllegalStateException("Не вдалося підключитися до PostgreSQL. Перевірте DB_PASSWORD і міграцію.", exception);
        }
    }

    private static long createPerformanceEquipment(String code) throws Exception {
        try (Connection connection = Database.connect()) {
            return new EquipmentRepository(connection)
                    .create(new Equipment(0, code, 1_200, 1_000_000))
                    .id();
        }
    }

    private static RunResult runWorkload(Workload workload, String baseUrl, long equipmentId, String token) throws Exception {
        List<RequestMetric> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService workers = Executors.newFixedThreadPool(workload.virtualUsers());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int virtualUser = 1; virtualUser <= workload.virtualUsers(); virtualUser++) {
            int user = virtualUser;
            futures.add(workers.submit(() -> {
                start.await();
                for (int iteration = 1; iteration <= workload.iterationsPerUser(); iteration++) {
                    runLifecycle(requests, workload.name(), user, iteration, baseUrl, equipmentId, token);
                }
                return null;
            }));
        }

        long startedAt = System.nanoTime();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        long durationNanos = System.nanoTime() - startedAt;
        workers.shutdown();

        List<RequestMetric> snapshot = new ArrayList<>(requests);
        snapshot.sort(Comparator.comparingInt(RequestMetric::virtualUser).thenComparingInt(RequestMetric::iteration));
        return summarize(workload, snapshot, durationNanos);
    }

    private static void runLifecycle(
            List<RequestMetric> requests,
            String profile,
            int virtualUser,
            int iteration,
            String baseUrl,
            long equipmentId,
            String token
    ) {
        String reference = token + "-" + profile + "-" + virtualUser + "-" + iteration;
        String createBody = "{\"reference\":\"" + reference + "\",\"equipmentId\":" + equipmentId
                + ",\"quantity\":1,\"days\":2,\"premium\":false}";

        ApiResponse created = request(
                requests, profile, virtualUser, iteration, "POST", "/rentals", createBody, baseUrl, 201,
                body -> body.path("id").asLong() > 0 && "Active".equals(body.path("status").asText())
        );
        if (!created.success()) return;

        long rentalId = created.body().path("id").asLong();
        ApiResponse loaded = request(
                requests, profile, virtualUser, iteration, "GET", "/rentals/" + rentalId, null, baseUrl, 200,
                body -> rentalId == body.path("id").asLong() && "Active".equals(body.path("status").asText())
        );
        if (!loaded.success()) return;

        ApiResponse returned = request(
                requests, profile, virtualUser, iteration, "POST", "/rentals/" + rentalId + "/return", "{}", baseUrl, 200,
                body -> rentalId == body.path("id").asLong() && "Returned".equals(body.path("status").asText())
        );
        if (!returned.success()) return;

        request(
                requests, profile, virtualUser, iteration, "GET", "/rentals/" + rentalId, null, baseUrl, 200,
                body -> rentalId == body.path("id").asLong() && "Returned".equals(body.path("status").asText())
        );
    }

    private static ApiResponse request(
            List<RequestMetric> requests,
            String profile,
            int virtualUser,
            int iteration,
            String method,
            String path,
            String body,
            String baseUrl,
            int expectedStatus,
            Predicate<JsonNode> responseCheck
    ) {
        long startedAt = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody = JSON.readTree(response.body());
            boolean passed = response.statusCode() == expectedStatus && responseCheck.test(responseBody);
            String detail = passed ? "" : "Expected HTTP " + expectedStatus + ", received " + response.statusCode();
            requests.add(metric(profile, virtualUser, iteration, method, path, response.statusCode(), startedAt, passed, detail));
            return new ApiResponse(responseBody, passed);
        } catch (Exception exception) {
            requests.add(metric(profile, virtualUser, iteration, method, path, 0, startedAt, false, exception.getClass().getSimpleName()));
            return new ApiResponse(JSON.nullNode(), false);
        }
    }

    private static RequestMetric metric(
            String profile,
            int virtualUser,
            int iteration,
            String method,
            String path,
            int status,
            long startedAt,
            boolean passed,
            String detail
    ) {
        return new RequestMetric(
                profile,
                virtualUser,
                iteration,
                method,
                path,
                status,
                (System.nanoTime() - startedAt) / 1_000_000.0,
                passed,
                detail
        );
    }

    private static RunResult summarize(Workload workload, List<RequestMetric> requests, long durationNanos) {
        List<Double> responseTimes = requests.stream().map(RequestMetric::responseTimeMs).sorted().toList();
        double average = responseTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        int p95Index = Math.max(0, (int) Math.ceil(responseTimes.size() * 0.95) - 1);
        double p95 = responseTimes.isEmpty() ? 0.0 : responseTimes.get(p95Index);
        long failures = requests.stream().filter(metric -> !metric.passed()).count();
        double errorRate = requests.isEmpty() ? 0.0 : failures * 100.0 / requests.size();
        double durationSeconds = durationNanos / 1_000_000_000.0;
        double throughput = durationSeconds == 0.0 ? 0.0 : requests.size() / durationSeconds;
        boolean accepted = p95 <= P95_THRESHOLD_MS && errorRate <= ERROR_RATE_THRESHOLD_PERCENT;
        return new RunResult(workload, requests, durationSeconds, average, p95, throughput, errorRate, accepted);
    }

    private static void writeRequestsCsv(Path file, List<RequestMetric> requests) throws Exception {
        StringBuilder csv = new StringBuilder("profile,virtual_user,iteration,method,path,status,response_time_ms,passed,detail\n");
        for (RequestMetric metric : requests) {
            csv.append(metric.profile()).append(',')
                    .append(metric.virtualUser()).append(',')
                    .append(metric.iteration()).append(',')
                    .append(metric.method()).append(',')
                    .append(metric.path()).append(',')
                    .append(metric.status()).append(',')
                    .append(String.format(Locale.US, "%.3f", metric.responseTimeMs())).append(',')
                    .append(metric.passed()).append(',')
                    .append(csvValue(metric.detail())).append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    }

    private static void writeSummaryCsv(Path file, List<RunResult> results) throws Exception {
        StringBuilder csv = new StringBuilder("profile,virtual_users,workflows,http_requests,duration_s,avg_response_ms,p95_response_ms,throughput_rps,error_rate_percent,p95_threshold_ms,error_rate_threshold_percent,accepted\n");
        for (RunResult result : results) {
            csv.append(result.workload().name()).append(',')
                    .append(result.workload().virtualUsers()).append(',')
                    .append(result.workload().virtualUsers() * result.workload().iterationsPerUser()).append(',')
                    .append(result.requests().size()).append(',')
                    .append(number(result.durationSeconds())).append(',')
                    .append(number(result.averageResponseMs())).append(',')
                    .append(number(result.p95ResponseMs())).append(',')
                    .append(number(result.throughputRps())).append(',')
                    .append(number(result.errorRatePercent())).append(',')
                    .append(number(P95_THRESHOLD_MS)).append(',')
                    .append(number(ERROR_RATE_THRESHOLD_PERCENT)).append(',')
                    .append(result.accepted()).append('\n');
        }
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    }

    private static void writeHtmlReport(Path file, List<RunResult> results, String token) throws Exception {
        double maxP95 = Math.max(P95_THRESHOLD_MS, results.stream().mapToDouble(RunResult::p95ResponseMs).max().orElse(1.0));
        StringBuilder rows = new StringBuilder();
        StringBuilder bars = new StringBuilder();
        int y = 55;
        for (RunResult result : results) {
            String status = result.accepted() ? "PASS" : "FAIL";
            rows.append("<tr><td>").append(result.workload().name()).append("</td><td>")
                    .append(result.workload().virtualUsers()).append("</td><td>")
                    .append(result.requests().size()).append("</td><td>")
                    .append(number(result.averageResponseMs())).append(" ms</td><td>")
                    .append(number(result.p95ResponseMs())).append(" ms</td><td>")
                    .append(number(result.throughputRps())).append(" rps</td><td>")
                    .append(number(result.errorRatePercent())).append("%</td><td class=\"")
                    .append(result.accepted() ? "pass" : "fail").append("\">").append(status).append("</td></tr>");
            double width = 510.0 * result.p95ResponseMs() / maxP95;
            bars.append("<text x=\"10\" y=\"").append(y + 17).append("\">").append(result.workload().name()).append("</text>")
                    .append("<rect x=\"120\" y=\"").append(y).append("\" width=\"").append(number(width))
                    .append("\" height=\"26\" fill=\"").append(result.accepted() ? "#15803d" : "#b91c1c").append("\"/>")
                    .append("<text x=\"").append(128 + width).append("\" y=\"").append(y + 18).append("\">")
                    .append(number(result.p95ResponseMs())).append(" ms</text>");
            y += 55;
        }
        double thresholdX = 120 + 510.0 * P95_THRESHOLD_MS / maxP95;
        String html = String.format(Locale.US, """
                <!doctype html>
                <html lang="uk"><head><meta charset="utf-8"><title>Lab 7 performance report</title>
                <style>body{font-family:Arial,sans-serif;max-width:1000px;margin:32px auto;color:#172033}h1{margin-bottom:4px}.muted{color:#566}table{border-collapse:collapse;width:100%%;margin:24px 0}th,td{border:1px solid #cbd5e1;padding:9px;text-align:left}th{background:#e2e8f0}.pass{color:#15803d;font-weight:bold}.fail{color:#b91c1c;font-weight:bold}.note{background:#eff6ff;padding:16px;border-left:4px solid #2563eb}svg{width:100%%;height:240px;border:1px solid #cbd5e1;background:#fff}text{font-size:14px;fill:#172033}</style>
                </head><body>
                <h1>Лабораторна робота №7: Performance and Load Testing</h1>
                <p class="muted">Run %s. Tool: Java HttpClient PerformanceTestRunner. API: local RentalApiServer with PostgreSQL.</p>
                <div class="note"><strong>Scenario:</strong> POST /rentals → GET /rentals/{id} → POST /rentals/{id}/return → GET /rentals/{id}. The id returned by POST is passed dynamically to each next request. Each workflow has a unique reference.</div>
                <h2>Workload profile</h2>
                <ul><li>Baseline: 1 virtual user, 10 workflows.</li><li>Load: 5 virtual users, 15 workflows per user.</li><li>Spike: 20 virtual users start simultaneously, 5 workflows per user, without ramp-up.</li></ul>
                <h2>Acceptance criteria</h2><p>p95 response time ≤ %.0f ms; error rate ≤ %.1f%%.</p>
                <h2>Results</h2><table><thead><tr><th>Profile</th><th>VUs</th><th>Requests</th><th>Average</th><th>p95</th><th>Throughput</th><th>Error rate</th><th>Result</th></tr></thead><tbody>%s</tbody></table>
                <h2>p95 response time chart</h2><svg viewBox="0 0 760 240"><line x1="%.2f" x2="%.2f" y1="20" y2="225" stroke="#dc2626" stroke-width="2" stroke-dasharray="5 5"/><text x="%.2f" y="18">threshold %.0f ms</text>%s</svg>
                <h2>Interpretation</h2><p>The report compares response-time growth, throughput and error rate between minimal, normal and sudden peak load. See report.md for an automatically generated short conclusion.</p>
                </body></html>
                """, token, P95_THRESHOLD_MS, ERROR_RATE_THRESHOLD_PERCENT, rows, thresholdX, thresholdX, thresholdX, P95_THRESHOLD_MS, bars);
        Files.writeString(file, html, StandardCharsets.UTF_8);
    }

    private static void writeMarkdownReport(Path file, List<RunResult> results, String token) throws Exception {
        StringBuilder report = new StringBuilder("# Lab 7 performance test report\n\n")
                .append("Run: `").append(token).append("`\n\n")
                .append("## Scenario\n\n")
                .append("`POST /rentals → GET /rentals/{id} → POST /rentals/{id}/return → GET /rentals/{id}`. ")
                .append("The `id` comes from the POST response and is used dynamically by the next requests.\n\n")
                .append("## Acceptance criteria\n\n")
                .append("- p95 response time <= ").append(number(P95_THRESHOLD_MS)).append(" ms\n")
                .append("- error rate <= ").append(number(ERROR_RATE_THRESHOLD_PERCENT)).append("%\n\n")
                .append("## Results\n\n")
                .append("| Profile | VUs | Requests | Average | p95 | Throughput | Error rate | Result |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---|\n");
        for (RunResult result : results) {
            report.append("| ").append(result.workload().name()).append(" | ")
                    .append(result.workload().virtualUsers()).append(" | ")
                    .append(result.requests().size()).append(" | ")
                    .append(number(result.averageResponseMs())).append(" ms | ")
                    .append(number(result.p95ResponseMs())).append(" ms | ")
                    .append(number(result.throughputRps())).append(" rps | ")
                    .append(number(result.errorRatePercent())).append("% | ")
                    .append(result.accepted() ? "PASS" : "FAIL").append(" |\n");
        }
        RunResult baseline = results.getFirst();
        RunResult spike = results.getLast();
        report.append("\n## Short conclusion\n\n")
                .append("The spike profile used ").append(spike.workload().virtualUsers()).append(" virtual users instead of ")
                .append(baseline.workload().virtualUsers()).append(" in baseline. ")
                .append("p95 changed from ").append(number(baseline.p95ResponseMs())).append(" ms to ")
                .append(number(spike.p95ResponseMs())).append(" ms, and throughput changed from ")
                .append(number(baseline.throughputRps())).append(" rps to ")
                .append(number(spike.throughputRps())).append(" rps. ")
                .append("The observed error rate under spike was ").append(number(spike.errorRatePercent())).append("%. ")
                .append("The threshold result is ").append(spike.accepted() ? "PASS." : "FAIL; inspect requests.csv for failures.").append("\n");
        Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
    }

    private static void printResult(RunResult result) {
        System.out.printf(
                Locale.US,
                "%s: requests=%d, avg=%.2f ms, p95=%.2f ms, throughput=%.2f rps, errors=%.2f%%, %s%n",
                result.workload().name(), result.requests().size(), result.averageResponseMs(), result.p95ResponseMs(),
                result.throughputRps(), result.errorRatePercent(), result.accepted() ? "PASS" : "FAIL"
        );
    }

    private static String number(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String csvValue(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record Workload(String name, int virtualUsers, int iterationsPerUser, String description) {
    }

    private record ApiResponse(JsonNode body, boolean success) {
    }

    private record RequestMetric(
            String profile,
            int virtualUser,
            int iteration,
            String method,
            String path,
            int status,
            double responseTimeMs,
            boolean passed,
            String detail
    ) {
    }

    private record RunResult(
            Workload workload,
            List<RequestMetric> requests,
            double durationSeconds,
            double averageResponseMs,
            double p95ResponseMs,
            double throughputRps,
            double errorRatePercent,
            boolean accepted
    ) {
    }
}

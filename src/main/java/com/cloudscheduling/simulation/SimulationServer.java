package com.cloudscheduling.simulation;

import com.cloudscheduling.scheduler.GlobalSchedulers;
import com.cloudscheduling.simulation.MultiGlobalComparisonSimulation.ComprehensiveResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Lightweight, zero-dependency HTTP REST API Server for SSP-ARR Cloud Task Scheduling.
 * Powered by JDK 21 built-in com.sun.net.httpserver.
 * 
 * Provides endpoints for cloud hosting (Render, Railway, Docker, Fly.io, etc.):
 *  - GET  /api/health      : Healthcheck for container orchestrators
 *  - GET  /api/results     : Returns latest simulation results JSON
 *  - GET  /api/simulate    : Triggers on-demand CloudSim Plus simulation
 *  - GET  /                : Serves static dashboard files or redirect
 */
public class SimulationServer {

    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isEmpty()) {
            try {
                port = Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) {}
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // Leverage Java 21 Virtual Threads

        // Routes
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/results", new ResultsHandler());
        server.createContext("/api/simulate", new SimulateHandler());
        server.createContext("/", new StaticFileHandler());

        server.start();
        System.out.println("==========================================================================");
        System.out.println(" SSP-ARR Cloud Scheduling Simulator Server ACTIVE");
        System.out.println(" Port: " + port + " (JDK 21 Virtual Threads)");
        System.out.println(" Health: http://localhost:" + port + "/api/health");
        System.out.println(" Results: http://localhost:" + port + "/api/results");
        System.out.println(" Simulate: http://localhost:" + port + "/api/simulate?policy=DYNAMIC_MAX_MIN&tasks=100");
        System.out.println("==========================================================================");
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String jsonResponse) throws IOException {
        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1) {
                params.put(pair[0], pair[1]);
            } else if (pair.length == 1) {
                params.put(pair[0], "");
            }
        }
        return params;
    }

    // GET /api/health
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = """
                {
                  "status": "UP",
                  "engine": "CloudSim Plus 8.5.7",
                  "version": "2.0",
                  "javaVersion": "%s",
                  "timestamp": "%s"
                }
                """.formatted(System.getProperty("java.version"), java.time.Instant.now().toString());
            sendJsonResponse(exchange, 200, json);
        }
    }

    // GET /api/results
    static class ResultsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            File jsonFile = new File("results/simulation_results.json");
            if (!jsonFile.exists()) {
                jsonFile = new File("dashboard/data/simulation_results.json");
            }

            if (jsonFile.exists()) {
                String content = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
                sendJsonResponse(exchange, 200, content);
            } else {
                sendJsonResponse(exchange, 404, "{\"error\": \"Simulation results not found. Run simulation first.\"}");
            }
        }
    }

    // GET /api/simulate?policy=DYNAMIC_MAX_MIN&tasks=100
    static class SimulateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
            String policyStr = params.getOrDefault("policy", "DYNAMIC_MAX_MIN");
            int tasks = 100;
            try {
                tasks = Integer.parseInt(params.getOrDefault("tasks", "100"));
            } catch (NumberFormatException ignored) {}

            try {
                GlobalSchedulers.Policy policy;
                try {
                    policy = GlobalSchedulers.Policy.valueOf(policyStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    policy = GlobalSchedulers.Policy.DYNAMIC_MAX_MIN;
                }

                System.out.printf("Running on-demand simulation: Policy=%s, Tasks=%d%n", policy.name(), tasks);
                long startTime = System.currentTimeMillis();

                // Run Classical RR baseline
                ComprehensiveResult rrResult = MultiGlobalComparisonSimulation.runSimulation(policy, tasks, true);

                // Run proposed SSP-ARR 2.0
                ComprehensiveResult sspResult = MultiGlobalComparisonSimulation.runSimulation(policy, tasks, false);

                long duration = System.currentTimeMillis() - startTime;

                double awtImp = ((rrResult.awtMs - sspResult.awtMs) / rrResult.awtMs) * 100.0;
                double atatImp = ((rrResult.atatS - sspResult.atatS) / rrResult.atatS) * 100.0;
                double msImp = ((rrResult.makespan - sspResult.makespan) / rrResult.makespan) * 100.0;
                double tpImp = ((sspResult.throughput - rrResult.throughput) / rrResult.throughput) * 100.0;
                double energyImp = ((rrResult.energyKWh - sspResult.energyKWh) / rrResult.energyKWh) * 100.0;
                double preemptImp = rrResult.totalPreemptions > 0 
                    ? ((double)(rrResult.totalPreemptions - sspResult.totalPreemptions) / rrResult.totalPreemptions) * 100.0 
                    : 0.0;

                String json = String.format(Locale.US, """
                    {
                      "status": "SUCCESS",
                      "executionTimeMs": %d,
                      "policy": "%s",
                      "tasks": %d,
                      "rr": {
                        "awtMs": %.2f,
                        "atatS": %.2f,
                        "actS": %.2f,
                        "makespan": %.2f,
                        "throughput": %.4f,
                        "energyKWh": %.4f,
                        "jfi": %.4f,
                        "lif": %.4f,
                        "preemptions": %d
                      },
                      "ssp": {
                        "awtMs": %.2f,
                        "atatS": %.2f,
                        "actS": %.2f,
                        "makespan": %.2f,
                        "throughput": %.4f,
                        "energyKWh": %.4f,
                        "jfi": %.4f,
                        "lif": %.4f,
                        "preemptions": %d
                      },
                      "improvement": {
                        "awtPercent": %.2f,
                        "atatPercent": %.2f,
                        "makespanPercent": %.2f,
                        "throughputPercent": %.2f,
                        "energyPercent": %.2f,
                        "preemptionsPercent": %.2f
                      }
                    }
                    """,
                    duration, policy.name(), tasks,
                    rrResult.awtMs, rrResult.atatS, rrResult.actS, rrResult.makespan, rrResult.throughput, rrResult.energyKWh, rrResult.jainsFairnessIndex, rrResult.loadImbalanceFactor, rrResult.totalPreemptions,
                    sspResult.awtMs, sspResult.atatS, sspResult.actS, sspResult.makespan, sspResult.throughput, sspResult.energyKWh, sspResult.jainsFairnessIndex, sspResult.loadImbalanceFactor, sspResult.totalPreemptions,
                    awtImp, atatImp, msImp, tpImp, energyImp, preemptImp
                );

                sendJsonResponse(exchange, 200, json);
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, String.format("{\"status\": \"ERROR\", \"message\": \"%s\"}", e.getMessage()));
            }
        }
    }

    // Static File Handler (Serves dashboard files if accessing Java server directly)
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }

            File file = new File("dashboard" + path);
            if (!file.exists() || file.isDirectory()) {
                file = new File("dashboard/index.html");
            }

            if (!file.exists()) {
                String notFound = "<h1>404 Not Found</h1>";
                exchange.sendResponseHeaders(404, notFound.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound.getBytes());
                }
                return;
            }

            String contentType = "text/plain";
            if (path.endsWith(".html")) contentType = "text/html; charset=UTF-8";
            else if (path.endsWith(".css")) contentType = "text/css; charset=UTF-8";
            else if (path.endsWith(".js")) contentType = "application/javascript; charset=UTF-8";
            else if (path.endsWith(".json")) contentType = "application/json; charset=UTF-8";
            else if (path.endsWith(".svg")) contentType = "image/svg+xml";

            byte[] bytes = Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}

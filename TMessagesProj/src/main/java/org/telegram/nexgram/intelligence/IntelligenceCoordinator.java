package org.telegram.nexgram.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * NEXGRAM Autonomous Intelligence coordinator.
 *
 * Pipeline (as defined by the master prompt):
 *  1. accepts the request
 *  2. Request Analyzer picks the modules that apply
 *  3. Module Registry provides implementations
 *  4. allowed adapters run in parallel
 *  5. Result Aggregator merges + deduplicates
 *  6. errors are isolated per module
 *  7. a structured report is produced
 *
 * No Telegram session data is ever passed to external services:
 * adapters receive only the query string typed by the user.
 */
public final class IntelligenceCoordinator {

    public static final class Report {
        public final List<IntelResult> results;
        public final List<String> errors;
        public final List<String> modulesUsed;

        Report(List<IntelResult> results, List<String> errors, List<String> modulesUsed) {
            this.results = results;
            this.errors = errors;
            this.modulesUsed = modulesUsed;
        }
    }

    public interface ReportCallback {
        void onReport(Report report);
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final List<IntelModule> registry = new ArrayList<>();

    public IntelligenceCoordinator() {
    }

    /** Registers a module (Search Adapters step). */
    public void register(IntelModule module) {
        if (module != null && !registry.contains(module)) {
            registry.add(module);
        }
    }

    /** Runs the full pipeline. Callback arrives on a background thread. */
    public void run(String query, ReportCallback callback) {
        if (query == null || query.trim().isEmpty()) {
            callback.onReport(new Report(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            return;
        }
        // 1-2-3: request analysis + registry lookup
        List<IntelModule> active = new ArrayList<>();
        for (IntelModule module : registry) {
            try {
                if (module.canHandle(query)) {
                    active.add(module);
                }
            } catch (Exception ignored) {
            }
        }
        if (active.isEmpty()) {
            ArrayList<String> errors = new ArrayList<>();
            errors.add("Жоден адаптер не застосовний до цього запиту");
            callback.onReport(new Report(new ArrayList<>(), errors, new ArrayList<>()));
            return;
        }

        // 4: run adapters in parallel with per-module error isolation
        LinkedHashMap<String, Future<List<IntelResult>>> futures = new LinkedHashMap<>();
        LinkedHashMap<String, Future<String>> errorFutures = new LinkedHashMap<>();
        // Each search() reports via callback; we wrap results collection here.
        List<QueryTask> tasks = new ArrayList<>();
        for (IntelModule module : active) {
            tasks.add(new QueryTask(module, query));
        }
        final List<QueryTask> finalTasks = tasks;
        executor.submit(() -> {
            List<Future<?>> handles = new ArrayList<>();
            for (QueryTask task : finalTasks) {
                handles.add(executor.submit(task::runSync));
            }
            for (Future<?> handle : handles) {
                try {
                    handle.get(12, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // timed out or interrupted; task stays isolated
                }
            }

            // 5-6: aggregate + collect errors
            LinkedHashMap<String, IntelResult> deduped = new LinkedHashMap<>();
            for (QueryTask task : finalTasks) {
                for (IntelResult result : task.results) {
                    String key = result.url == null ? result.title : result.url;
                    if (!deduped.containsKey(key)) {
                        deduped.put(key, result);
                    }
                }
            }
            List<IntelResult> results = new ArrayList<>(deduped.values());
            results.sort((a, b) -> Integer.compare(b.confidence, a.confidence));

            List<String> modulesUsed = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (QueryTask task : finalTasks) {
                modulesUsed.add(task.module.id());
                if (task.error != null) {
                    errors.add(task.module.id() + ": " + task.error);
                }
            }

            // 7: report
            callback.onReport(new Report(results, errors, modulesUsed));
        });
    }

    private static final class QueryTask {
        final IntelModule module;
        final String query;
        final List<IntelResult> results = new ArrayList<>();
        String error;

        QueryTask(IntelModule module, String query) {
            this.module = module;
            this.query = query;
        }

        void runSync() {
            try {
                module.search(query, (results1, error1) -> {
                    if (results1 != null) {
                        results.addAll(results1);
                    }
                    error = error1;
                });
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            }
        }
    }
}

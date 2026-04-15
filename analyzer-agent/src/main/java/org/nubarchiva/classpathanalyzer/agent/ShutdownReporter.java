package org.nubarchiva.classpathanalyzer.agent;

import org.nubarchiva.classpathanalyzer.common.model.ClassLoadEvent;
import org.nubarchiva.classpathanalyzer.common.model.RuntimeAnalysisResult;
import org.nubarchiva.classpathanalyzer.common.util.JsonSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Writes accumulated results to disk. Aggregates events incrementally on each flush
 * to keep memory bounded: only unique JARs, classes and ClassLoader chains are retained,
 * not individual events.
 */
public class ShutdownReporter {

    private final EventQueue eventQueue;
    private final AgentConfig config;

    // Incrementally aggregated data — bounded by unique JARs/classes, not event count
    private final Map<String, Set<String>> jarToClasses =
            Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> conflictResolutions =
            Collections.synchronizedMap(new HashMap<>());
    private final Set<String> classLoaderHierarchies =
            Collections.synchronizedSet(new LinkedHashSet<>());

    // Only populated when events=true
    private final List<ClassLoadEvent> allEvents =
            Collections.synchronizedList(new ArrayList<>());

    private long aggregatedCount = 0;

    public ShutdownReporter(EventQueue eventQueue, AgentConfig config) {
        this.eventQueue = eventQueue;
        this.config = config;
    }

    /**
     * Drains pending events, aggregates them into the running maps, and writes to disk.
     * Individual events are discarded after aggregation (unless events=true).
     */
    public synchronized void flush() {
        List<ClassLoadEvent> drained = eventQueue.drain();
        if (drained.isEmpty()) return;

        aggregate(drained);

        try {
            writeResult();
        } catch (IOException e) {
            System.err.println("[agent] Error writing snapshot: " + e.getMessage());
        }
    }

    /**
     * Final write: drains everything, aggregates, and writes.
     */
    public synchronized void finalWrite() {
        List<ClassLoadEvent> drained = eventQueue.drain();
        if (!drained.isEmpty()) {
            aggregate(drained);
        }

        System.err.println("[agent] === Agent summary ===");
        System.err.println("[agent] Aggregated events: " + aggregatedCount);
        System.err.println("[agent] Total events (including throttle): " + eventQueue.getTotalCount());
        System.err.println("[agent] Dropped events (throttle): " + eventQueue.getDroppedCount());
        System.err.println("[agent] Unique JARs loaded: " + jarToClasses.size());
        System.err.println("[agent] Unique classes loaded: " + conflictResolutions.size());

        try {
            writeResult();
            System.err.println("[agent] Result written to: " + config.getOutputDir());
        } catch (IOException e) {
            System.err.println("[agent] ERROR writing final result: " + e.getMessage());
        }
    }

    private void aggregate(List<ClassLoadEvent> events) {
        for (ClassLoadEvent event : events) {
            if (event.getSourceJar() != null) {
                jarToClasses.computeIfAbsent(event.getSourceJar(), k ->
                        Collections.synchronizedSet(new HashSet<>()))
                        .add(event.getClassName());
                conflictResolutions.putIfAbsent(event.getClassName(), event.getSourceJar());
            }
            if (event.getClassLoaderHierarchy() != null) {
                classLoaderHierarchies.add(event.getClassLoaderHierarchy());
            }
        }
        aggregatedCount += events.size();

        if (config.isIncludeEvents()) {
            allEvents.addAll(events);
        }
        // When events=false, individual events are discarded here — only aggregated data remains
    }

    private void writeResult() throws IOException {
        RuntimeAnalysisResult result = new RuntimeAnalysisResult();
        result.setAnalysisTimestamp(Instant.now());

        if (config.isIncludeEvents()) {
            result.setLoadEvents(new ArrayList<>(allEvents));
        }

        // Copy aggregated maps (snapshot under synchronization)
        synchronized (jarToClasses) {
            HashMap<String, Set<String>> copy = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : jarToClasses.entrySet()) {
                copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            result.setJarToLoadedClasses(copy);
        }
        synchronized (conflictResolutions) {
            result.setConflictResolutions(new HashMap<>(conflictResolutions));
        }
        synchronized (classLoaderHierarchies) {
            result.setClassLoaderHierarchy(new ArrayList<>(classLoaderHierarchies));
        }

        // Calculate neverLoadedJars
        HashSet<String> neverLoaded = new HashSet<>();
        String classPath = System.getProperty("java.class.path", "");
        String separator = System.getProperty("path.separator", ":");
        for (String entry : classPath.split(separator)) {
            if (entry.endsWith(".jar")) {
                String jarName = entry.contains("/")
                        ? entry.substring(entry.lastIndexOf('/') + 1)
                        : entry.contains("\\")
                                ? entry.substring(entry.lastIndexOf('\\') + 1)
                                : entry;
                if (!jarToClasses.containsKey(jarName)) {
                    neverLoaded.add(jarName);
                }
            }
        }
        result.setNeverLoadedJars(neverLoaded);

        Files.createDirectories(config.getOutputDir());
        String label = config.getLabel();
        String fileName = "runtime-analysis-result"
                + (label != null ? "-" + label : "")
                + ".json";
        Path outputFile = config.getOutputDir().resolve(fileName);
        JsonSerializer.writeToFile(result, outputFile);
    }
}

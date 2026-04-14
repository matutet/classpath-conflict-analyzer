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
 * Writes accumulated results to disk. Runs:
 * - Periodically according to flush-interval (partial writes)
 * - On JVM shutdown via shutdown hook (final write)
 */
public class ShutdownReporter {

    private final EventQueue eventQueue;
    private final AgentConfig config;
    private final List<ClassLoadEvent> allEvents = Collections.synchronizedList(new ArrayList<>());

    public ShutdownReporter(EventQueue eventQueue, AgentConfig config) {
        this.eventQueue = eventQueue;
        this.config = config;
    }

    /**
     * Drains pending events from the queue and accumulates them internally.
     * Writes a partial snapshot to disk.
     */
    public void flush() {
        List<ClassLoadEvent> drained = eventQueue.drain();
        allEvents.addAll(drained);

        try {
            writeResult("partial");
        } catch (IOException e) {
            System.err.println("[agent] Error writing partial snapshot: " + e.getMessage());
        }
    }

    /**
     * Final write: drains everything, builds the complete result and writes it.
     */
    public void finalWrite() {
        List<ClassLoadEvent> drained = eventQueue.drain();
        allEvents.addAll(drained);

        System.err.println("[agent] === Agent summary ===");
        System.err.println("[agent] Captured events: " + allEvents.size());
        System.err.println("[agent] Total events (including throttle): " + eventQueue.getTotalCount());
        System.err.println("[agent] Dropped events (throttle): " + eventQueue.getDroppedCount());

        try {
            writeResult("final");
            System.err.println("[agent] Result written to: " + config.getOutputDir());
        } catch (IOException e) {
            System.err.println("[agent] ERROR writing final result: " + e.getMessage());
        }
    }

    private void writeResult(String suffix) throws IOException {
        RuntimeAnalysisResult result = buildResult();
        Files.createDirectories(config.getOutputDir());
        String label = config.getLabel();
        String fileName = "runtime-analysis-result"
                + (label != null ? "-" + label : "")
                + "-" + suffix + ".json";
        Path outputFile = config.getOutputDir().resolve(fileName);
        JsonSerializer.writeToFile(result, outputFile);
    }

    private RuntimeAnalysisResult buildResult() {
        RuntimeAnalysisResult result = new RuntimeAnalysisResult();
        result.setAnalysisTimestamp(Instant.now());
        result.setLoadEvents(new ArrayList<>(allEvents));

        // Build aggregated maps
        HashMap<String, Set<String>> jarToClasses = new HashMap<>();
        LinkedHashSet<String> classLoaderHierarchies = new LinkedHashSet<>();
        HashMap<String, String> conflictResolutions = new HashMap<>();

        for (ClassLoadEvent event : allEvents) {
            if (event.getSourceJar() != null) {
                jarToClasses.computeIfAbsent(event.getSourceJar(), k -> new HashSet<>())
                        .add(event.getClassName());
                // The first JAR to load the class is the "winner"
                conflictResolutions.putIfAbsent(event.getClassName(), event.getSourceJar());
            }
            if (event.getClassLoaderHierarchy() != null) {
                classLoaderHierarchies.add(event.getClassLoaderHierarchy());
            }
        }

        result.setJarToLoadedClasses(jarToClasses);
        result.setConflictResolutions(conflictResolutions);
        result.setClassLoaderHierarchy(new ArrayList<>(classLoaderHierarchies));

        // Calculate neverLoadedJars: JARs in the classpath that did not load any class
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

        return result;
    }
}

package org.nubarchiva.classpathanalyzer.report;

import org.nubarchiva.classpathanalyzer.common.model.RuntimeAnalysisResult;
import org.nubarchiva.classpathanalyzer.common.util.JsonSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CLI tool that reads one or more agent runtime JSONs + scans JARs on disk,
 * and produces a human-readable conflict report.
 */
public class ReportMain {

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return;
        }

        List<Path> runtimeJsons = new ArrayList<>();
        Path jarsDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runtime":
                    for (var part : args[++i].split(",")) {
                        Path path = Path.of(part.trim());
                        if (Files.isDirectory(path)) {
                            try (var stream = Files.newDirectoryStream(path, "*.json")) {
                                for (Path jsonFile : stream) {
                                    runtimeJsons.add(jsonFile);
                                }
                            } catch (IOException e) {
                                System.err.println("ERROR: cannot read directory " + path + ": " + e.getMessage());
                                System.exit(1);
                            }
                        } else {
                            runtimeJsons.add(path);
                        }
                    }
                    break;
                case "--jars":
                    jarsDir = Path.of(args[++i]);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    return;
            }
        }

        if (runtimeJsons.isEmpty() || jarsDir == null) {
            System.err.println("ERROR: both --runtime and --jars are required");
            printUsage();
            System.exit(1);
        }

        try {
            run(runtimeJsons, jarsDir);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void run(List<Path> runtimeJsons, Path jarsDir) throws Exception {
        // Load and merge agent results
        RuntimeAnalysisResult merged = loadAndMerge(runtimeJsons);
        var loadedJarCount = merged.getJarToLoadedClasses().size();
        var neverLoadedCount = merged.getNeverLoadedJars().size();
        System.err.println("[report] Merged: " + loadedJarCount + " JARs loaded, " +
                neverLoadedCount + " never loaded");

        // Scan JARs on disk
        System.err.println("[report] Scanning JARs: " + jarsDir);
        var scanner = new JarScanner();
        scanner.scan(jarsDir);

        // Analyze conflicts
        System.err.println("[report] Analyzing conflicts...");
        var analyzer = new ConflictAnalyzer(scanner.getJarToClasses(), merged);
        var conflicts = analyzer.analyzeConflicts();
        System.err.println("[report] " + conflicts.size() + " JAR pairs with duplicate classes");
        System.err.println();

        // Print report to stdout
        printConflicts(conflicts);
        printNeverLoaded(merged);
        printSummary(scanner, merged, conflicts);
    }

    /**
     * Loads one or more runtime JSONs and merges them into a single result.
     * jarToLoadedClasses and neverLoadedJars are unioned, conflictResolutions
     * keeps the first winner for each class.
     */
    private static RuntimeAnalysisResult loadAndMerge(List<Path> jsonPaths) throws Exception {
        RuntimeAnalysisResult merged = new RuntimeAnalysisResult();
        Map<String, Set<String>> mergedJarToClasses = new HashMap<>();
        Map<String, String> mergedConflicts = new HashMap<>();
        Set<String> allNeverLoaded = null;
        Set<String> mergedHierarchies = new LinkedHashSet<>();

        for (Path path : jsonPaths) {
            System.err.println("[report] Loading: " + path);
            RuntimeAnalysisResult r = JsonSerializer.readFromFile(path, RuntimeAnalysisResult.class);

            // Merge jarToLoadedClasses (union)
            for (var entry : r.getJarToLoadedClasses().entrySet()) {
                mergedJarToClasses
                        .computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                        .addAll(entry.getValue());
            }

            // Merge conflictResolutions (first wins)
            for (var entry : r.getConflictResolutions().entrySet()) {
                mergedConflicts.putIfAbsent(entry.getKey(), entry.getValue());
            }

            // Merge neverLoadedJars (intersection: only JARs never loaded in ALL runs)
            if (allNeverLoaded == null) {
                allNeverLoaded = new HashSet<>(r.getNeverLoadedJars());
            } else {
                allNeverLoaded.retainAll(r.getNeverLoadedJars());
            }

            // Merge classLoaderHierarchy
            mergedHierarchies.addAll(r.getClassLoaderHierarchy());
        }

        merged.setJarToLoadedClasses(mergedJarToClasses);
        merged.setConflictResolutions(mergedConflicts);
        merged.setNeverLoadedJars(allNeverLoaded != null ? allNeverLoaded : new HashSet<>());
        merged.setClassLoaderHierarchy(new ArrayList<>(mergedHierarchies));

        return merged;
    }

    private static void printConflicts(List<ConflictAnalyzer.JarPairConflict> conflicts) {
        if (conflicts.isEmpty()) {
            System.out.println("NO DUPLICATE CLASSES FOUND");
            System.out.println();
            return;
        }

        System.out.println("=== CONFLICTS: JARS WITH DUPLICATE CLASSES ===");
        System.out.println();

        for (var c : conflicts) {
            var danger = (c.jarALoaded && c.jarBLoaded) ? " *** BOTH LOADED ***" : "";
            System.out.println(c.jarA + "  <>  " + c.jarB + danger);

            System.out.println();
            System.out.println("  " + c.duplicateClassCount + " duplicate classes:");
            if (c.duplicateWonByA > 0 || c.duplicateWonByB > 0 || c.duplicateNotLoaded > 0) {
                System.out.println("    Loaded from " + c.jarA + ": " + c.duplicateWonByA);
                System.out.println("    Loaded from " + c.jarB + ": " + c.duplicateWonByB);
                if (c.duplicateNotLoaded > 0) {
                    System.out.println("    Not loaded: " + c.duplicateNotLoaded);
                }
            }

            System.out.println();
            System.out.println("  Exclusive classes:");
            System.out.println("    " + c.jarA + ": " + c.exclusiveClassesA +
                    " (" + c.exclusiveLoadedA + " loaded)");
            System.out.println("    " + c.jarB + ": " + c.exclusiveClassesB +
                    " (" + c.exclusiveLoadedB + " loaded)");

            System.out.println();
        }
    }

    private static void printNeverLoaded(RuntimeAnalysisResult runtime) {
        var neverLoaded = runtime.getNeverLoadedJars();
        if (neverLoaded.isEmpty()) return;

        System.out.println("=== NEVER LOADED JARS (" + neverLoaded.size() + ") ===");
        System.out.println();
        for (var jar : neverLoaded.stream().sorted().collect(java.util.stream.Collectors.toList())) {
            System.out.println("  " + jar);
        }
        System.out.println();
    }

    private static void printSummary(JarScanner scanner, RuntimeAnalysisResult runtime,
                                     List<ConflictAnalyzer.JarPairConflict> conflicts) {
        var bothLoaded = conflicts.stream()
                .filter(c -> c.jarALoaded && c.jarBLoaded)
                .count();

        System.out.println("=== SUMMARY ===");
        System.out.println("  JARs on disk:          " + scanner.getJarToClasses().size());
        System.out.println("  JARs loaded at runtime: " + runtime.getJarToLoadedClasses().size());
        System.out.println("  JARs never loaded:     " + runtime.getNeverLoadedJars().size());
        System.out.println("  JAR pairs with duplicate classes: " + conflicts.size());
        System.out.println("  JAR pairs both loaded (conflicts): " + bothLoaded);
    }

    private static void printUsage() {
        System.out.println("Classpath Conflict Analyzer — Report");
        System.out.println();
        System.out.println("Crosses agent runtime data with JAR contents to find real conflicts.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar analyzer-report.jar --runtime <path> --jars <dir>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --runtime <path>   Agent output: a JSON file, a directory of JSONs, or");
        System.out.println("                     comma-separated paths (files and/or directories)");
        System.out.println("  --jars <dir>       Directory containing the JARs to analyze");
        System.out.println();
        System.out.println("When multiple JSONs are provided (e.g., from concurrent Surefire forks),");
        System.out.println("they are merged: loaded classes are unioned, never-loaded JARs are intersected.");
    }
}

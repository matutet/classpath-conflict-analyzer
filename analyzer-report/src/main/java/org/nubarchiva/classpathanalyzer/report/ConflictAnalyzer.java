package org.nubarchiva.classpathanalyzer.report;

import org.nubarchiva.classpathanalyzer.common.model.RuntimeAnalysisResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Crosses JAR contents with agent runtime data to find real conflicts.
 */
public class ConflictAnalyzer {

    private final Map<String, Set<String>> jarToClasses;
    private final RuntimeAnalysisResult runtime;

    public ConflictAnalyzer(Map<String, Set<String>> jarToClasses, RuntimeAnalysisResult runtime) {
        this.jarToClasses = jarToClasses;
        this.runtime = runtime;
    }

    /**
     * Finds all duplicate classes: classes that exist in more than one JAR.
     * Returns a map: class -> set of JARs that contain it.
     */
    public Map<String, Set<String>> findDuplicates() {
        var classToJars = new HashMap<String, Set<String>>();
        for (var entry : jarToClasses.entrySet()) {
            var jar = entry.getKey();
            for (var cls : entry.getValue()) {
                classToJars.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(jar);
            }
        }

        // Keep only duplicates
        var duplicates = new LinkedHashMap<String, Set<String>>();
        for (var entry : classToJars.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.put(entry.getKey(), entry.getValue());
            }
        }
        return duplicates;
    }

    /**
     * For each pair of JARs that share duplicate classes, produces a conflict report:
     * - How many classes they share
     * - Which JAR loaded the duplicate classes (winner)
     * - Whether each JAR also has exclusive classes that were loaded
     */
    public List<JarPairConflict> analyzeConflicts() {
        var duplicates = findDuplicates();
        var loadedFrom = runtime.getConflictResolutions();
        var loadedJars = runtime.getJarToLoadedClasses();

        // Group duplicates by JAR pair
        var pairToDuplicateClasses = new LinkedHashMap<String, Map<String, Set<String>>>();
        for (var entry : duplicates.entrySet()) {
            var cls = entry.getKey();
            var jars = new ArrayList<>(entry.getValue());
            Collections.sort(jars);

            for (int i = 0; i < jars.size(); i++) {
                for (int j = i + 1; j < jars.size(); j++) {
                    var pairKey = jars.get(i) + " | " + jars.get(j);
                    pairToDuplicateClasses
                            .computeIfAbsent(pairKey, k -> new LinkedHashMap<>())
                            .computeIfAbsent(cls, k -> new LinkedHashSet<>());

                    // Record which JAR loaded this class
                    var winner = loadedFrom.get(cls);
                    if (winner != null) {
                        pairToDuplicateClasses.get(pairKey).get(cls).add(winner);
                    }
                }
            }
        }

        // Global set of all duplicated classes (across all JARs, not just pairs)
        var allDuplicatedClasses = duplicates.keySet();

        // Build conflict reports
        var conflicts = new ArrayList<JarPairConflict>();
        for (var entry : pairToDuplicateClasses.entrySet()) {
            var parts = entry.getKey().split(" \\| ", 2);
            var jarA = parts[0];
            var jarB = parts[1];
            var duplicateClasses = entry.getValue();

            var conflict = new JarPairConflict();
            conflict.jarA = jarA;
            conflict.jarB = jarB;
            conflict.duplicateClassCount = duplicateClasses.size();

            // Which JAR won each duplicate class
            var wonByA = 0;
            var wonByB = 0;
            var notLoaded = 0;
            for (var classEntry : duplicateClasses.entrySet()) {
                var winners = classEntry.getValue();
                if (winners.contains(jarA)) wonByA++;
                else if (winners.contains(jarB)) wonByB++;
                else notLoaded++;
            }
            conflict.duplicateWonByA = wonByA;
            conflict.duplicateWonByB = wonByB;
            conflict.duplicateNotLoaded = notLoaded;

            // Exclusive classes: classes in this JAR that are NOT duplicated in ANY other JAR
            var allClassesA = jarToClasses.getOrDefault(jarA, Collections.emptySet());
            var allClassesB = jarToClasses.getOrDefault(jarB, Collections.emptySet());

            var exclusiveA = allClassesA.stream()
                    .filter(c -> !allDuplicatedClasses.contains(c))
                    .collect(Collectors.toSet());
            var exclusiveB = allClassesB.stream()
                    .filter(c -> !allDuplicatedClasses.contains(c))
                    .collect(Collectors.toSet());

            var loadedClassesA = loadedJars.getOrDefault(jarA, Collections.emptySet());
            var loadedClassesB = loadedJars.getOrDefault(jarB, Collections.emptySet());

            conflict.exclusiveClassesA = exclusiveA.size();
            conflict.exclusiveLoadedA = (int) exclusiveA.stream().filter(loadedClassesA::contains).count();
            conflict.exclusiveClassesB = exclusiveB.size();
            conflict.exclusiveLoadedB = (int) exclusiveB.stream().filter(loadedClassesB::contains).count();

            conflict.jarALoaded = loadedJars.containsKey(jarA);
            conflict.jarBLoaded = loadedJars.containsKey(jarB);

            conflicts.add(conflict);
        }

        // Sort: conflicts where both JARs are loaded first (most dangerous)
        conflicts.sort((a, b) -> {
            var bothA = a.jarALoaded && a.jarBLoaded ? 0 : 1;
            var bothB = b.jarALoaded && b.jarBLoaded ? 0 : 1;
            if (bothA != bothB) return bothA - bothB;
            return Integer.compare(b.duplicateClassCount, a.duplicateClassCount);
        });

        return conflicts;
    }

    public static class JarPairConflict {
        public String jarA;
        public String jarB;
        public int duplicateClassCount;
        public int duplicateWonByA;
        public int duplicateWonByB;
        public int duplicateNotLoaded;
        public int exclusiveClassesA;
        public int exclusiveLoadedA;
        public int exclusiveClassesB;
        public int exclusiveLoadedB;
        public boolean jarALoaded;
        public boolean jarBLoaded;
    }
}

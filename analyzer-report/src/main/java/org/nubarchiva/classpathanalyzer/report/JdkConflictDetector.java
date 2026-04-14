package org.nubarchiva.classpathanalyzer.report;

import java.util.*;

/**
 * Detects classes in JARs that duplicate packages provided by the JDK.
 * Uses the module system (Java 9+) to discover JDK packages at runtime.
 */
public class JdkConflictDetector {

    /**
     * A JAR that contains classes overlapping with JDK module packages.
     */
    public static class JdkConflict {
        public final String jarName;
        public final String jdkModule;
        public final int overlappingClasses;
        public final List<String> sampleClasses;

        JdkConflict(String jarName, String jdkModule, int overlappingClasses, List<String> sampleClasses) {
            this.jarName = jarName;
            this.jdkModule = jdkModule;
            this.overlappingClasses = overlappingClasses;
            this.sampleClasses = sampleClasses;
        }
    }

    private final Map<String, String> packageToModule;

    public JdkConflictDetector() {
        this.packageToModule = discoverJdkPackages();
    }

    /**
     * Discovers all packages exported by JDK modules in the current runtime.
     */
    private Map<String, String> discoverJdkPackages() {
        Map<String, String> result = new HashMap<>();
        for (Module module : ModuleLayer.boot().modules()) {
            String moduleName = module.getName();
            for (String pkg : module.getPackages()) {
                result.put(pkg, moduleName);
            }
        }
        return result;
    }

    /**
     * For each JAR, finds classes whose package is also provided by a JDK module.
     */
    public List<JdkConflict> detect(Map<String, Set<String>> jarToClasses) {
        var conflicts = new ArrayList<JdkConflict>();

        for (var entry : jarToClasses.entrySet()) {
            var jarName = entry.getKey();
            var classes = entry.getValue();

            // Group overlapping classes by JDK module
            var moduleToClasses = new LinkedHashMap<String, List<String>>();

            for (var cls : classes) {
                var lastDot = cls.lastIndexOf('.');
                if (lastDot <= 0) continue;
                var pkg = cls.substring(0, lastDot);

                var jdkModule = packageToModule.get(pkg);
                if (jdkModule != null) {
                    moduleToClasses.computeIfAbsent(jdkModule, k -> new ArrayList<>()).add(cls);
                }
            }

            for (var moduleEntry : moduleToClasses.entrySet()) {
                var allClasses = moduleEntry.getValue();
                var sample = allClasses.subList(0, Math.min(5, allClasses.size()));
                conflicts.add(new JdkConflict(jarName, moduleEntry.getKey(),
                        allClasses.size(), new ArrayList<>(sample)));
            }
        }

        conflicts.sort((a, b) -> Integer.compare(b.overlappingClasses, a.overlappingClasses));
        return conflicts;
    }

    public int getJdkPackageCount() {
        return packageToModule.size();
    }
}

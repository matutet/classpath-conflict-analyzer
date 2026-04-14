package org.nubarchiva.classpathanalyzer.report;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Scans JAR files and builds an index of which classes each JAR contains.
 */
public class JarScanner {

    private final Map<String, Set<String>> jarToClasses = new LinkedHashMap<>();

    public void scan(Path jarsDir) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jarsDir, "*.jar")) {
            for (var jarPath : stream) {
                try {
                    scanJar(jarPath);
                } catch (IOException e) {
                    System.err.println("[scan] WARNING: skipping unreadable JAR " +
                            jarPath.getFileName() + ": " + e.getMessage());
                    continue;
                }
                count++;
                if (count % 100 == 0) {
                    System.err.println("[scan] " + count + " JARs scanned...");
                }
            }
        }
        System.err.println("[scan] " + count + " JARs scanned");
    }

    private void scanJar(Path jarPath) throws IOException {
        var fileName = jarPath.getFileName().toString();
        var classes = new HashSet<String>();

        try (var jarFile = new JarFile(jarPath.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
        }

        jarToClasses.put(fileName, classes);
    }

    public Map<String, Set<String>> getJarToClasses() {
        return jarToClasses;
    }
}

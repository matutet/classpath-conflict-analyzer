package org.nubarchiva.classpathanalyzer.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Agent configuration, parsed from the -javaagent argstring.
 * Format: comma-separated key=value pairs.
 * E.g.: output=/tmp/analysis,exclude=com.internal.:org.private.
 */
public class AgentConfig {

    private static final Set<String> DEFAULT_EXCLUDES = new HashSet<>(Arrays.asList(
            "java.", "javax.", "sun.", "jdk.", "com.sun.", "org.xml.", "org.w3c."
    ));

    private Path outputDir = Paths.get(System.getProperty("java.io.tmpdir"), "classpath-analyzer");
    private String label = null;
    private Set<String> excludePrefixes = new HashSet<>(DEFAULT_EXCLUDES);
    private boolean includeJdk = false;
    private boolean includeEvents = false;
    private int flushIntervalSeconds = 30;

    /**
     * Parses the agent argstring.
     */
    public static AgentConfig parse(String agentArgs) {
        AgentConfig config = new AgentConfig();
        if (agentArgs == null || agentArgs.isEmpty()) {
            return config;
        }

        for (String param : agentArgs.split(",")) {
            String[] parts = param.split("=", 2);
            if (parts.length != 2) continue;

            String key = parts[0].trim();
            String value = parts[1].trim();

            switch (key) {
                case "output":
                    config.outputDir = Paths.get(value);
                    break;
                case "exclude":
                    for (String prefix : value.split(":")) {
                        if (!prefix.isEmpty()) {
                            config.excludePrefixes.add(prefix);
                        }
                    }
                    break;
                case "include-jdk":
                    config.includeJdk = Boolean.parseBoolean(value);
                    if (config.includeJdk) {
                        config.excludePrefixes.removeAll(DEFAULT_EXCLUDES);
                    }
                    break;
                case "flush-interval":
                    try {
                        config.flushIntervalSeconds = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        System.err.println("[agent] Invalid flush-interval '" + value + "', using default 30s");
                    }
                    break;
                case "label":
                    config.label = value;
                    break;
                case "events":
                    config.includeEvents = Boolean.parseBoolean(value);
                    break;
                default:
                    System.err.println("[agent] Unknown parameter: " + key);
                    break;
            }
        }

        return config;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public String getLabel() {
        return label;
    }

    public Set<String> getExcludePrefixes() {
        return excludePrefixes;
    }

    public boolean isIncludeJdk() {
        return includeJdk;
    }

    public boolean isIncludeEvents() {
        return includeEvents;
    }

    public int getFlushIntervalSeconds() {
        return flushIntervalSeconds;
    }

    /**
     * Checks whether a class should be excluded based on the configured prefixes.
     */
    public boolean shouldExclude(String className) {
        for (String prefix : excludePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

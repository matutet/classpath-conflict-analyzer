package org.nubarchiva.classpathanalyzer.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.*;

/**
 * Complete runtime analysis result: class loading events,
 * JAR-to-loaded-classes mapping, never-loaded JARs, and conflict resolution.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeAnalysisResult {

    private Instant analysisTimestamp;
    private List<ClassLoadEvent> loadEvents = new ArrayList<>();
    private Map<String, Set<String>> jarToLoadedClasses = new HashMap<>();
    private Set<String> neverLoadedJars = new HashSet<>();
    private Map<String, String> conflictResolutions = new HashMap<>();
    private List<String> classLoaderHierarchy = new ArrayList<>();

    public RuntimeAnalysisResult() {
    }

    public Instant getAnalysisTimestamp() { return analysisTimestamp; }
    public void setAnalysisTimestamp(Instant analysisTimestamp) { this.analysisTimestamp = analysisTimestamp; }

    public List<ClassLoadEvent> getLoadEvents() { return loadEvents; }
    public void setLoadEvents(List<ClassLoadEvent> loadEvents) { this.loadEvents = loadEvents; }

    public Map<String, Set<String>> getJarToLoadedClasses() { return jarToLoadedClasses; }
    public void setJarToLoadedClasses(Map<String, Set<String>> jarToLoadedClasses) { this.jarToLoadedClasses = jarToLoadedClasses; }

    public Set<String> getNeverLoadedJars() { return neverLoadedJars; }
    public void setNeverLoadedJars(Set<String> neverLoadedJars) { this.neverLoadedJars = neverLoadedJars; }

    public Map<String, String> getConflictResolutions() { return conflictResolutions; }
    public void setConflictResolutions(Map<String, String> conflictResolutions) { this.conflictResolutions = conflictResolutions; }

    public List<String> getClassLoaderHierarchy() { return classLoaderHierarchy; }
    public void setClassLoaderHierarchy(List<String> classLoaderHierarchy) { this.classLoaderHierarchy = classLoaderHierarchy; }
}

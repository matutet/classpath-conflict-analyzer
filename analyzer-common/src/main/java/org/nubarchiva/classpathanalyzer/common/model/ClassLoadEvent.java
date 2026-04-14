package org.nubarchiva.classpathanalyzer.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Class loading event captured at runtime by the agent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClassLoadEvent {

    private String className;
    private String sourceJar;
    private String classLoaderName;
    private String classLoaderHierarchy;
    private long timestampMs;
    private String triggerThread;

    public ClassLoadEvent() {
    }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getSourceJar() { return sourceJar; }
    public void setSourceJar(String sourceJar) { this.sourceJar = sourceJar; }

    public String getClassLoaderName() { return classLoaderName; }
    public void setClassLoaderName(String classLoaderName) { this.classLoaderName = classLoaderName; }

    public String getClassLoaderHierarchy() { return classLoaderHierarchy; }
    public void setClassLoaderHierarchy(String classLoaderHierarchy) { this.classLoaderHierarchy = classLoaderHierarchy; }

    public long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    public String getTriggerThread() { return triggerThread; }
    public void setTriggerThread(String triggerThread) { this.triggerThread = triggerThread; }
}

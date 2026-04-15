package org.nubarchiva.classpathanalyzer.agent;

import org.nubarchiva.classpathanalyzer.common.model.ClassLoadEvent;

import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClassFileTransformer that intercepts the loading of each class to record
 * a ClassLoadEvent with complete metadata.
 *
 * IMPORTANT: Does not transform bytecode (returns null). Only observes.
 * Uses a ThreadLocal to prevent infinite recursion (ClassCircularityError)
 * when the agent itself needs to load classes during instrumentation.
 */
public class ClassLoadInterceptor implements ClassFileTransformer {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final EventQueue eventQueue;
    private final AgentConfig config;
    private final ConcurrentHashMap<ClassLoader, String> hierarchyCache = new ConcurrentHashMap<>();

    public ClassLoadInterceptor(EventQueue eventQueue, AgentConfig config) {
        this.eventQueue = eventQueue;
        this.config = config;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // Prevent recursion: if already inside transform, exit
        if (ACTIVE.get()) {
            return null;
        }

        ACTIVE.set(Boolean.TRUE);
        try {
            if (className == null) return null;

            String fqcn = className.replace('/', '.');

            // Exclude configured packages
            if (config.shouldExclude(fqcn)) return null;

            // Exclude agent's own classes to avoid recursion
            if (fqcn.startsWith("org.nubarchiva.classpathanalyzer.agent")) return null;

            ClassLoadEvent event = new ClassLoadEvent();
            event.setClassName(fqcn);
            event.setTimestampMs(System.currentTimeMillis());
            event.setTriggerThread(Thread.currentThread().getName());

            // Extract source JAR from ProtectionDomain.CodeSource
            if (protectionDomain != null && protectionDomain.getCodeSource() != null) {
                URL location = protectionDomain.getCodeSource().getLocation();
                if (location != null) {
                    String path = location.getPath();
                    if (path.endsWith(".jar") || path.contains(".jar!")) {
                        event.setSourceJar(extractJarName(path));
                    }
                }
            }

            // Record ClassLoader
            if (loader != null) {
                event.setClassLoaderName(loader.getClass().getName());
                event.setClassLoaderHierarchy(
                        hierarchyCache.computeIfAbsent(loader, this::buildClassLoaderHierarchy));
            } else {
                event.setClassLoaderName("BootstrapClassLoader");
            }

            eventQueue.offer(event);

        } catch (Throwable t) {
            // Catch any error to avoid interfering with the application
            // Do not use logging to prevent recursion
        } finally {
            ACTIVE.remove();
        }

        // Never transform bytecode
        return null;
    }

    /**
     * Extracts the JAR file name from a URL/path.
     */
    private String extractJarName(String path) {
        // Handle formats like: /path/to/lib.jar, file:/path/to/lib.jar, jar:file:/path/to/lib.jar!/
        String cleaned = path;
        int bangIdx = cleaned.indexOf('!');
        if (bangIdx >= 0) cleaned = cleaned.substring(0, bangIdx);

        int slashIdx = cleaned.lastIndexOf('/');
        return slashIdx >= 0 ? cleaned.substring(slashIdx + 1) : cleaned;
    }

    /**
     * Builds the full ClassLoader hierarchy from the current one up to bootstrap.
     */
    private String buildClassLoaderHierarchy(ClassLoader loader) {
        StringBuilder sb = new StringBuilder();
        ClassLoader current = loader;
        while (current != null) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(current.getClass().getName());
            current = current.getParent();
        }
        sb.append(" -> BootstrapClassLoader");
        return sb.toString();
    }
}

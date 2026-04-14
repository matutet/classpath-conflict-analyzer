package org.nubarchiva.classpathanalyzer.agent;

import java.lang.instrument.Instrumentation;

/**
 * Entry point for the Java agent (-javaagent).
 * Registers a ClassFileTransformer to intercept class loading,
 * a daemon thread for periodic flushing, and a shutdown hook for the final write.
 */
public class ClasspathAnalyzerAgent {

    /**
     * Premain method invoked by the JVM before the application's main method.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.err.println("[agent] Classpath Conflict Analyzer Agent starting...");

        AgentConfig config = AgentConfig.parse(agentArgs);
        System.err.println("[agent] Output: " + config.getOutputDir());
        System.err.println("[agent] Flush interval: " + config.getFlushIntervalSeconds() + "s");

        EventQueue eventQueue = new EventQueue();
        ShutdownReporter reporter = new ShutdownReporter(eventQueue, config);

        // Register the class loading interceptor
        ClassLoadInterceptor interceptor = new ClassLoadInterceptor(eventQueue, config);
        inst.addTransformer(interceptor, false);

        // Daemon thread for periodic flushing
        Thread flushThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(config.getFlushIntervalSeconds() * 1000L);
                    reporter.flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "classpath-analyzer-flush");
        flushThread.setDaemon(true);
        flushThread.start();

        // Shutdown hook: stop flush thread, wait for it to finish, then write final result
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flushThread.interrupt();
            try {
                flushThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reporter.finalWrite();
        }, "classpath-analyzer-shutdown"));

        System.err.println("[agent] Agent ready — intercepting class loads");
    }
}

# Architecture

## What It Does

Captures every class loaded by the JVM at runtime and records which JAR it came from.
Produces a JSON report showing what was loaded, what was never loaded, and how the
ClassLoader hierarchy resolved conflicts.

This fills the gap that static analysis tools (`jdeps`, `dependency:analyze`,
`maven-enforcer`) cannot cover: dependencies loaded via ServiceLoader, `Class.forName`,
or framework/XML configuration.

## How It Works

```
YOUR APPLICATION (running with -javaagent)
    │
    ▼
ClassLoadInterceptor (ClassFileTransformer)
    │ observes each class load, never modifies bytecode
    │ records: class name, source JAR, ClassLoader, thread, timestamp
    ▼
EventQueue (ConcurrentLinkedQueue)
    │ lock-free, automatic throttling at 100k events
    ▼
ShutdownReporter (shutdown hook + periodic flush)
    │ aggregates events into jarToLoadedClasses, neverLoadedJars, conflictResolutions
    ▼
runtime-analysis-result-final.json
```

## Shade and Relocation

The agent JAR must be self-contained because it loads before the application classpath.
All dependencies are embedded with package relocation to avoid conflicts:

```
com.fasterxml.jackson → org.nubarchiva.classpathanalyzer.agent.shaded.jackson
org.nubarchiva.classpathanalyzer.common → org.nubarchiva.classpathanalyzer.agent.shaded.common
```

## ClassCircularityError Prevention

When a `ClassFileTransformer` intercepts a class load, it may need to load its own
classes (e.g., to create a `ClassLoadEvent`). This causes recursive loading and
`ClassCircularityError`. The interceptor uses a `ThreadLocal<Boolean>` guard:

```java
if (ACTIVE.get()) return null;  // already inside transform, skip
ACTIVE.set(true);
try { ... } finally { ACTIVE.set(false); }
```

## Throttling

If the event queue exceeds 100,000 pending events, the agent samples 1 in every 10
and logs a warning. Throttle deactivates when the queue drops below 50,000.

## neverLoadedJars

At shutdown, the agent reads `java.class.path`, extracts JAR filenames, and computes
the difference against JARs that contributed at least one loaded class. The result is
the set of JARs that were on the classpath but never used.

## Known Limitations

| Limitation | Details |
|---|---|
| JDK classes excluded by default | `java.*`, `javax.*`, `sun.*`, `jdk.*`, `com.sun.*`, `org.xml.*`, `org.w3c.*` are not captured. Use `include-jdk=true` to override. |
| `include-jdk=true` on Java 9+ | JDK module classes have `jrt:/` URLs, not `.jar` paths. With `include-jdk=true` these classes are captured but `sourceJar` will be null — they won't appear in `jarToLoadedClasses` or `conflictResolutions`. |
| Dynamically loaded JARs | `neverLoadedJars` is computed from `java.class.path`. JARs added at runtime via custom `URLClassLoader` or similar mechanisms are not tracked. |
| Conditional code paths | Classes only loaded under specific conditions won't appear unless those paths execute during the capture. |
| Short-lived applications | If the JVM exits before the flush interval, use a shorter `flush-interval` or rely on the shutdown hook. |

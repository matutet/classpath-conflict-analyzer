# Architecture

## What It Does

Two tools that work together:

1. **Agent** — Attaches to a running JVM and captures every class load: which class,
   from which JAR, by which ClassLoader.
2. **Report** — Takes the agent's output + the actual JARs on disk and finds conflicts:
   duplicates between JARs, duplicates between JARs and the JDK, and JARs never loaded.

This fills the gap that static analysis tools (`jdeps`, `dependency:analyze`,
`maven-enforcer`) cannot cover: dependencies loaded via ServiceLoader, `Class.forName`,
or framework/XML configuration.

## Data Flow

```
YOUR APPLICATION (running with -javaagent)
    │
    ▼
Agent: ClassLoadInterceptor (ClassFileTransformer)
    │ observes each class load, never modifies bytecode
    │ records: class name, source JAR, ClassLoader, thread, timestamp
    ▼
Agent: EventQueue (ConcurrentLinkedQueue)
    │ lock-free, automatic throttling at 100k events
    ▼
Agent: ShutdownReporter (shutdown hook + periodic flush)
    │ aggregates into jarToLoadedClasses, neverLoadedJars, conflictResolutions
    ▼
runtime-analysis-result.json
    │
    ▼
Report: ReportMain
    │ loads agent JSON(s) + scans JARs on disk
    ├── JdkConflictDetector: finds JARs that duplicate JDK module packages
    ├── ConflictAnalyzer: finds duplicate classes between JARs
    │   and crosses with runtime data to show which JAR "won"
    ▼
stdout: human-readable conflict report
```

## Agent Design

### Shade and relocation

The agent JAR must be self-contained because it loads before the application classpath.
All dependencies are embedded with package relocation to avoid conflicts:

```
com.fasterxml.jackson → org.nubarchiva.classpathanalyzer.agent.shaded.jackson
org.nubarchiva.classpathanalyzer.common → org.nubarchiva.classpathanalyzer.agent.shaded.common
```

### ClassCircularityError prevention

When a `ClassFileTransformer` intercepts a class load, it may need to load its own
classes (e.g., to create a `ClassLoadEvent`). This causes recursive loading and
`ClassCircularityError`. The interceptor uses a `ThreadLocal<Boolean>` guard:

```java
if (ACTIVE.get()) return null;  // already inside transform, skip
ACTIVE.set(true);
try { ... } finally { ACTIVE.set(false); }
```

### Throttling

If the event queue exceeds 100,000 pending events, the agent samples 1 in every 10
and logs a warning. Throttle deactivates when the queue drops below 50,000.

### neverLoadedJars

At shutdown, the agent reads `java.class.path`, extracts JAR filenames, and computes
the difference against JARs that contributed at least one loaded class.

### Output file

A single file (`runtime-analysis-result.json`, or `runtime-analysis-result-{label}.json`
with the `label` parameter) is written on each flush and at shutdown. Each write
overwrites the previous one with the latest aggregated data.

## Report Design

### JAR-vs-JDK conflict detection

`JdkConflictDetector` queries `ModuleLayer.boot()` at runtime to discover all packages
provided by JDK modules. It then checks each class in each scanned JAR: if the class's
package is also in a JDK module, it's a conflict. This detects the root cause of ECJ
errors like "The package X is accessible from more than one module".

No hardcoded list is needed — the JDK packages are discovered from the runtime itself.

### JAR-vs-JAR conflict detection

`ConflictAnalyzer` builds an inverted index (class → JARs) from the scanned JARs, finds
duplicates, and crosses them with the agent's `conflictResolutions` to show which JAR
"won" each conflict. For each JAR pair, it also reports exclusive classes (not duplicated
in any other JAR) and how many of those were loaded.

### Multi-JSON merge

When multiple agent JSONs are provided (e.g., from concurrent Surefire forks), the report
merges them: `jarToLoadedClasses` is unioned, `neverLoadedJars` is intersected (only JARs
never loaded in ALL runs), `conflictResolutions` keeps the first winner.

## Known Limitations

| Limitation | Details |
|---|---|
| JDK classes excluded by default | `java.*`, `javax.*`, `sun.*`, `jdk.*`, `com.sun.*`, `org.xml.*`, `org.w3c.*` are not captured by the agent. Use `include-jdk=true` to override. |
| `include-jdk=true` on Java 9+ | JDK module classes have `jrt:/` URLs, not `.jar` paths. `sourceJar` will be null — they won't appear in `jarToLoadedClasses`. |
| Dynamically loaded JARs | `neverLoadedJars` is computed from `java.class.path`. JARs added at runtime via custom `URLClassLoader` are not tracked. |
| Conditional code paths | Classes only loaded under specific conditions won't appear unless those paths execute during the capture. |

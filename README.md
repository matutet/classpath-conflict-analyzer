# Classpath Conflict Analyzer

Two tools for understanding classpath conflicts in Java applications:

1. **Agent** — Java agent (`-javaagent`) that captures every class loaded at runtime:
   which class, from which JAR, by which ClassLoader.
2. **Report** — Crosses the agent's output with the actual JARs on disk to find
   duplicate classes, show which JAR "won" each conflict, and identify JARs that
   were never loaded.

Use them to find out which JARs are really needed at runtime — especially for
dependencies loaded via ServiceLoader, `Class.forName`, or framework configuration
that static analysis tools (`jdeps`, `dependency:analyze`, `maven-enforcer`) cannot see.

## Quick Start

```bash
# Build
mvn clean package

# Run your application with the agent — no parameters needed
java -javaagent:analyzer-agent/target/analyzer-agent-1.0.0-SNAPSHOT.jar \
  -jar your-app.jar
```

The agent works with zero configuration. At startup it prints where the output will go:

```
[agent] Classpath Conflict Analyzer Agent starting...
[agent] Output: /tmp/classpath-analyzer
[agent] Agent ready — intercepting class loads
```

When the JVM shuts down, the result is written to `runtime-analysis-result.json`
in that directory. Override the location with `output=`:

```bash
java -javaagent:analyzer-agent.jar=output=/my/path -jar your-app.jar
```

## What It Captures

For every class loaded (excluding JDK internals by default):

- **Class name** (FQCN)
- **Source JAR** — which JAR provided the class
- **ClassLoader** — name and full delegation hierarchy
- **Thread** — which thread triggered the load
- **Timestamp**

The final report aggregates this into:

- **jarToLoadedClasses** — for each JAR, which of its classes were actually loaded
- **neverLoadedJars** — JARs on the classpath that contributed zero loaded classes
- **conflictResolutions** — for each class, which JAR "won" (was loaded first)
- **classLoaderHierarchy** — all unique ClassLoader chains observed

## Agent Parameters

Passed as comma-separated `key=value` pairs in the `-javaagent` argument:

| Parameter | Default | Description |
|---|---|---|
| `output` | `$TMPDIR/classpath-analyzer` | Directory for JSON output |
| `label` | _(none)_ | Included in the output filename. Use to distinguish multiple runs (e.g., `fork-1`, `integration`) |
| `exclude` | `java.:javax.:sun.:jdk.:com.sun.:org.xml.:org.w3c.` | Package prefixes to ignore (colon-separated). Additional prefixes are appended to defaults. |
| `include-jdk` | `false` | Set to `true` to also capture JDK class loads (removes default excludes) |
| `events` | `false` | Set to `true` to include individual load events in the JSON output. Off by default to keep output small. |
| `flush-interval` | `30` | Seconds between partial writes to disk (protection against abrupt shutdown) |

## Output Format

The agent writes `runtime-analysis-result.json` (overwritten periodically as a safety net
against abrupt shutdown). Fields:

| Field | Type | What it tells you |
|---|---|---|
| `analysisTimestamp` | ISO 8601 | When the report was generated |
| `jarToLoadedClasses` | `{jar: [classes]}` | For each JAR, which of its classes were actually loaded. If a JAR is here, something used it. |
| `neverLoadedJars` | `[jars]` | JARs present on `java.class.path` that contributed zero loaded classes. These are candidates for removal. |
| `conflictResolutions` | `{class: jar}` | When a class exists in multiple JARs, which JAR "won" (was loaded first by the ClassLoader). |
| `classLoaderHierarchy` | `[chains]` | All unique ClassLoader delegation chains observed (e.g., `WebAppClassLoader -> AppClassLoader -> BootstrapClassLoader`). |
| `loadEvents` | `[events]` | Raw list of every class load event (class, JAR, ClassLoader, thread, timestamp). Large — use `jarToLoadedClasses` and `neverLoadedJars` for summaries. |

## Example

```bash
java -javaagent:analyzer-agent-1.0.0-SNAPSHOT.jar=output=/tmp/analysis,flush-interval=10 \
  -jar my-legacy-app.jar
```

Output (`/tmp/analysis/runtime-analysis-result.json`):

```json
{
  "analysisTimestamp": "2026-04-14T16:00:00Z",
  "jarToLoadedClasses": {
    "spring-core-5.3.jar": ["org.springframework.core.SpringVersion", "..."],
    "slf4j-simple-2.0.9.jar": ["org.slf4j.simple.SimpleLogger", "..."]
  },
  "neverLoadedJars": [
    "commons-collections-3.2.2.jar",
    "xml-apis-1.4.01.jar"
  ],
  "conflictResolutions": {
    "org.w3c.dom.Document": "xercesImpl-2.12.2.jar"
  },
  "classLoaderHierarchy": [
    "jdk.internal.loader.ClassLoaders$AppClassLoader -> ... -> BootstrapClassLoader"
  ]
}
```

## Conflict Report

After running the agent, use the report tool to cross the runtime data with the JARs
on disk and find the real conflicts.

First, extract all dependency JARs to a directory (if you haven't already):

```bash
cd /path/to/your/project
mvn dependency:copy-dependencies -DoutputDirectory=target/dependency
```

Then run the report:

```bash
java -jar analyzer-report/target/analyzer-report-1.0.0-SNAPSHOT.jar \
  --runtime /tmp/analysis/runtime-analysis-result.json \
  --jars /path/to/your/project/target/dependency
```

The `--runtime` argument accepts a single file, a directory (loads all `*.json` inside),
or comma-separated paths mixing both:

```bash
# Single file
java -jar analyzer-report.jar --runtime /tmp/analysis/runtime-analysis-result.json --jars target/dependency

# Directory (loads all *.json files)
java -jar analyzer-report.jar --runtime /tmp/analysis --jars target/dependency

# Multiple files (e.g., from concurrent Surefire forks)
java -jar analyzer-report.jar --runtime result-fork-1.json,result-fork-2.json --jars target/dependency
```

Output:

```
=== CONFLICTS: JARS WITH DUPLICATE CLASSES ===

xercesImpl-2.12.2.jar  <>  xml-apis-1.4.01.jar *** BOTH LOADED ***

  247 duplicate classes:
    Loaded from xercesImpl-2.12.2.jar: 35
    Loaded from xml-apis-1.4.01.jar: 0
    Not loaded: 212

  Exclusive classes:
    xercesImpl-2.12.2.jar: 689 (128 loaded)
    xml-apis-1.4.01.jar: 0 (0 loaded)

=== NEVER LOADED JARS (4) ===

  commons-collections-3.2.2.jar
  unused-legacy-lib-1.0.jar

=== SUMMARY ===
  JARs on disk:          45
  JARs loaded at runtime: 41
  JARs never loaded:     4
  JAR pairs with duplicate classes: 12
  JAR pairs both loaded (conflicts): 3
```

This tells you:
- Which JAR pairs share classes and which JAR "won" each conflict
- Whether each JAR also has exclusive classes that were loaded (meaning you can't just remove it)
- Which JARs were never loaded at all

## Use Cases

### Standalone application

```bash
java -javaagent:analyzer-agent.jar=output=/tmp/analysis \
  -jar my-app.jar
```

### Maven Surefire (unit tests)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>-javaagent:/path/to/analyzer-agent.jar=output=${project.build.directory}/classpath-analysis</argLine>
    </configuration>
</plugin>
```

### Maven Failsafe (integration tests)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <argLine>-javaagent:/path/to/analyzer-agent.jar=output=${project.build.directory}/classpath-analysis,label=integration</argLine>
    </configuration>
</plugin>
```

### Surefire / Failsafe with concurrent forks

When `forkCount > 1`, each fork runs a separate JVM. Use the `label` parameter with
Surefire's `${surefire.forkNumber}` to avoid output collisions:

```xml
<configuration>
    <forkCount>4</forkCount>
    <argLine>-javaagent:/path/to/analyzer-agent.jar=output=${project.build.directory}/classpath-analysis,label=fork-${surefire.forkNumber}</argLine>
</configuration>
```

This produces separate files: `runtime-analysis-result-fork-1.json`,
`runtime-analysis-result-fork-2.json`, etc. Point the report tool at the directory:

```bash
java -jar analyzer-report.jar \
  --runtime target/classpath-analysis \
  --jars target/dependency
```

The report merges them automatically: loaded classes are unioned (a class loaded in any
fork counts as loaded), never-loaded JARs are intersected (only JARs never loaded in
ALL forks are reported as never loaded).

### Spring Boot

```bash
java -javaagent:analyzer-agent.jar=output=/tmp/analysis \
  -jar my-spring-boot-app.jar
```

### Application server (Tomcat, Jetty standalone)

Add the agent to `JAVA_OPTS` or `CATALINA_OPTS`:

```bash
export CATALINA_OPTS="-javaagent:/path/to/analyzer-agent.jar=output=/tmp/analysis"
```

### Docker

Add the agent JAR to the image and reference it in the entrypoint:

```dockerfile
COPY analyzer-agent.jar /opt/agent/analyzer-agent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/agent/analyzer-agent.jar=output=/tmp/analysis"
```

`JAVA_TOOL_OPTIONS` is picked up automatically by all JVMs, no entrypoint changes needed.

## Design

The agent is a single fat JAR with all dependencies (Jackson, shared models) relocated
via `maven-shade-plugin` to avoid version conflicts with the analyzed application:

```
com.fasterxml.jackson → org.nubarchiva.classpathanalyzer.agent.shaded.jackson
```

It registers a `ClassFileTransformer` that **observes but never modifies** bytecode.
A `ThreadLocal` guard prevents `ClassCircularityError` from recursive class loading.
Events accumulate in a lock-free `ConcurrentLinkedQueue` with automatic throttling
(1-in-10 sampling) if the queue exceeds 100,000 events.

## Prerequisites

- Java 11+
- Maven 3.8+ (to build)

## License

Apache License 2.0 — see [LICENSE](LICENSE).

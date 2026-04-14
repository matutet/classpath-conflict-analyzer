# Classpath Conflict Analyzer

Two tools for understanding classpath conflicts in Java applications:

1. **Agent** (`-javaagent`) — Captures every class loaded at runtime: which class,
   from which JAR, by which ClassLoader. Produces a JSON file.
2. **Report** — Crosses the agent's JSON with the actual JARs on disk to find:
   - JARs that duplicate classes from JDK modules (the cause of ECJ/module errors)
   - JARs that duplicate classes between each other, and which one "won"
   - JARs that were never loaded at all

Use them to find out which JARs are really needed at runtime — especially for
dependencies loaded via ServiceLoader, `Class.forName`, or framework configuration
that static tools (`jdeps`, `dependency:analyze`, `maven-enforcer`) cannot see.

For architecture and design details, see [docs/architecture.md](docs/architecture.md).
For development, see [docs/development-guide.md](docs/development-guide.md).

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

When the JVM shuts down, the result is in `runtime-analysis-result.json`.
Override the location with `output=`:

```bash
java -javaagent:analyzer-agent.jar=output=/my/path -jar your-app.jar
```

## Agent Parameters

Passed as comma-separated `key=value` pairs in the `-javaagent` argument:

| Parameter | Default | Description |
|---|---|---|
| `output` | `$TMPDIR/classpath-analyzer` | Directory for JSON output |
| `label` | _(none)_ | Included in the output filename. Use to distinguish multiple runs (e.g., `fork-1`, `integration`) |
| `exclude` | `java.:javax.:sun.:jdk.:com.sun.:org.xml.:org.w3c.` | Package prefixes to ignore (colon-separated). Additional prefixes are appended to defaults |
| `include-jdk` | `false` | Set to `true` to also capture JDK class loads (removes default excludes) |
| `events` | `false` | Set to `true` to include individual load events in the JSON. Off by default to keep output small |
| `flush-interval` | `30` | Seconds between writes to disk (protection against abrupt shutdown) |

## Agent Output Format

The JSON file contains:

| Field | What it tells you |
|---|---|
| `jarToLoadedClasses` | For each JAR, which of its classes were loaded. If a JAR is here, something used it. |
| `neverLoadedJars` | JARs on `java.class.path` that contributed zero loaded classes. |
| `conflictResolutions` | When a class exists in multiple JARs, which JAR was loaded first. |
| `classLoaderHierarchy` | All unique ClassLoader delegation chains observed. |
| `loadEvents` | Individual load events (only when `events=true`). |

## Conflict Report

After running the agent, use the report tool to find the real conflicts.

First, extract all dependency JARs to a directory:

```bash
cd /path/to/your/project
mvn dependency:copy-dependencies -DoutputDirectory=target/dependency
```

Then run the report:

```bash
java -jar analyzer-report/target/analyzer-report-1.0.0-SNAPSHOT.jar \
  --runtime /tmp/classpath-analyzer/runtime-analysis-result.json \
  --jars target/dependency
```

The `--runtime` argument accepts a file, a directory (loads all `*.json` inside),
or comma-separated paths:

```bash
# Directory (loads all *.json files — useful for concurrent forks)
java -jar analyzer-report.jar --runtime target/classpath-analysis --jars target/dependency
```

### Report output

```
=== JARS DUPLICATING JDK CLASSES ===

xml-apis-1.4.01.jar  <>  JDK module java.xml
  183 classes in packages provided by the JDK:
    org.w3c.dom.Document
    org.w3c.dom.Element
    org.xml.sax.SAXException
    ... and 178 more

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
  JARs on disk:            45
  JARs loaded at runtime:  41
  JARs never loaded:       4
  JARs duplicating JDK:    3
  JAR-vs-JAR conflicts:    12
  JAR-vs-JAR both loaded:  3
```

## Use Cases

### Standalone application

```bash
java -javaagent:analyzer-agent.jar -jar my-app.jar
```

### Maven Surefire (unit tests)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>-javaagent:${settings.localRepository}/org/nubarchiva/tools/analyzer-agent/1.0.0-SNAPSHOT/analyzer-agent-1.0.0-SNAPSHOT.jar=output=${project.build.directory}/classpath-analysis</argLine>
    </configuration>
</plugin>
```

### Maven Failsafe (integration tests)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <argLine>-javaagent:${settings.localRepository}/org/nubarchiva/tools/analyzer-agent/1.0.0-SNAPSHOT/analyzer-agent-1.0.0-SNAPSHOT.jar=output=${project.build.directory}/classpath-analysis,label=integration</argLine>
    </configuration>
</plugin>
```

### Surefire / Failsafe with concurrent forks

When `forkCount > 1`, each fork runs a separate JVM. Use the `label` parameter with
Surefire's `${surefire.forkNumber}` to avoid output collisions:

```xml
<configuration>
    <forkCount>4</forkCount>
    <argLine>-javaagent:${settings.localRepository}/org/nubarchiva/tools/analyzer-agent/1.0.0-SNAPSHOT/analyzer-agent-1.0.0-SNAPSHOT.jar=output=${project.build.directory}/classpath-analysis,label=fork-${surefire.forkNumber}</argLine>
</configuration>
```

Then point the report at the output directory:

```bash
java -jar analyzer-report.jar --runtime target/classpath-analysis --jars target/dependency
```

The report merges all JSONs automatically: loaded classes are unioned, never-loaded
JARs are intersected.

### Spring Boot

```bash
java -javaagent:analyzer-agent.jar -jar my-spring-boot-app.jar
```

### Application server (Tomcat, Jetty standalone)

```bash
export CATALINA_OPTS="-javaagent:/path/to/analyzer-agent.jar"
```

### Docker

```dockerfile
COPY analyzer-agent.jar /opt/agent/analyzer-agent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/agent/analyzer-agent.jar"
```

`JAVA_TOOL_OPTIONS` is picked up automatically by all JVMs.

## Prerequisites

- **Agent**: Java 8+ (compatible with Java 8 through 21+)
- **Report**: Java 11+
- **Build**: Maven 3.8+

## License

Apache License 2.0 — see [LICENSE](LICENSE).

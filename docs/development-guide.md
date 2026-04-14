# Development Guide

## Build

```bash
mvn clean package
```

Produces two fat JARs:
- `analyzer-agent/target/analyzer-agent-1.0.0-SNAPSHOT.jar` (Java 8 compatible)
- `analyzer-report/target/analyzer-report-1.0.0-SNAPSHOT.jar` (Java 11)

## Modules

```
analyzer-common (Java 8)  ←── analyzer-agent (Java 8, shaded)
                          ←── analyzer-report (Java 11)
```

- **analyzer-common** — `ClassLoadEvent`, `RuntimeAnalysisResult`, `JsonSerializer`
- **analyzer-agent** — Fat JAR with shade + relocation. MANIFEST has `Premain-Class` and `Can-Retransform-Classes: true`.
- **analyzer-report** — Fat JAR. Reads agent JSON + scans JARs on disk to produce conflict report.

## Key Classes

### Agent
- `ClasspathAnalyzerAgent` — `premain()` entry point
- `ClassLoadInterceptor` — `ClassFileTransformer` that records events
- `EventQueue` — Lock-free buffer with throttling
- `AgentConfig` — Parses `-javaagent` argument string
- `ShutdownReporter` — Aggregates and writes results at flush/shutdown

### Report
- `ReportMain` — CLI entry point, loads/merges runtime JSONs
- `ConflictAnalyzer` — Crosses JAR contents with runtime data to find duplicates
- `JarScanner` — Indexes classes inside each JAR file

## Verify the agent MANIFEST

```bash
unzip -p analyzer-agent/target/analyzer-agent-1.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF
```

Must contain:
```
Premain-Class: org.nubarchiva.classpathanalyzer.agent.ClasspathAnalyzerAgent
Can-Retransform-Classes: true
```

## Verify relocation

```bash
jar tf analyzer-agent/target/analyzer-agent-1.0.0-SNAPSHOT.jar | grep shaded | head
```

Should show relocated packages under `org/nubarchiva/classpathanalyzer/agent/shaded/`.

## Adding dependencies to the agent

Any new dependency MUST be relocated in `analyzer-agent/pom.xml` to avoid conflicts
with the analyzed application. Add a `<relocation>` block in the shade plugin config.

`jackson-datatype-jsr310` is currently a transitive dependency of `analyzer-common`.
If that dependency changes, it must be declared explicitly in `analyzer-agent/pom.xml`.

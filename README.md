# MultiprocessorModel

A desktop simulator of cache memory in a shared-bus multiprocessor system (Swing GUI, UI in Russian).

You configure the modeled system — number of processors, main memory size, cache size, block size and associativity, memory/bus timings, and the cache-coherence protocol (MSI or MESI) — along with workload parameters such as the probability of memory accesses, the read/write ratio, and the share of accesses to memory shared between processors. The simulator then runs the model on live threads (one per processor and cache controller, plus the system bus and a monitor) and reports:

- cache hit rate per processor and system-wide
- average memory-cycle time
- processor load factors
- overall system performance

A detailed event log of processor requests, cache lookups, and bus transactions is shown while the simulation runs.

The project was created in NetBeans (~2010) and is based on the discontinued Swing Application Framework (JSR-296). The required libraries — `appframework`, `swing-worker`, and NetBeans `AbsoluteLayout` — are bundled in `lib/`.

## Requirements

- **To run: Java 8.** The simulation uses `Thread.stop()`, which was removed in Java 20+, so newer runtimes cannot stop a running simulation. `run.sh` looks for the Oracle JRE 8 at `/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java` (macOS) and falls back to `java` on the `PATH`.
- **To build: any JDK whose `javac` still supports `--release 8`** (JDK 9–25). `build.sh` defaults to the JDK bundled with IntelliJ IDEA (`~/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/javac`) and falls back to `javac` on the `PATH`; set the `JAVAC` environment variable to override.

## Building

```sh
./build.sh
```

Compiles the sources against the jars in `lib/` and produces `dist/CacheModeler_MultyprocessorVersion.jar` with its dependencies in `dist/lib/`.

## Launching

```sh
./run.sh
```

Runs the jar from `dist/` on Java 8. To launch manually:

```sh
cd dist
java -jar CacheModeler_MultyprocessorVersion.jar
```

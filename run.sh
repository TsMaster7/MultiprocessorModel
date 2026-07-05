#!/bin/sh
# Runs the cache-memory simulator.
# IMPORTANT: must run on Java 8 (the code uses Thread.stop(), which was
# removed in Java 20+ — on a modern JVM the "stop modeling" action would fail).
set -e
cd "$(dirname "$0")/dist"

JAVA8="/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java"
[ -x "$JAVA8" ] || JAVA8=java

exec "$JAVA8" -jar CacheModeler_MultyprocessorVersion.jar "$@"

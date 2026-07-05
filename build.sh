#!/bin/sh
# Builds dist/CacheModeler_MultyprocessorVersion.jar
# Needs a JDK with javac supporting --release 8. No JDK is installed system-wide,
# so we default to the one bundled with IntelliJ IDEA (JetBrains Runtime).
set -e
cd "$(dirname "$0")"

JAVAC="${JAVAC:-/Users/tarassoroka/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/javac}"
[ -x "$JAVAC" ] || JAVAC=javac

CP="lib/appframework-1.03.jar:lib/swing-worker-1.1.jar:lib/AbsoluteLayout-RELEASE126.jar"

rm -rf build/classes
mkdir -p build/classes dist/lib

"$JAVAC" --release 8 -encoding UTF-8 -Xlint:-options -cp "$CP" \
    -d build/classes src/cachemodeler_multyprocessor/*.java

# resources (properties, icons, META-INF/services)
rsync -a --exclude="*.java" --exclude="*.form" src/ build/classes/

# assemble the jar (plain zip with a manifest works; JBR ships no `jar` tool)
mkdir -p build/classes/META-INF
printf 'Manifest-Version: 1.0\nMain-Class: cachemodeler_multyprocessor.CacheModeler_MultyprocessorVersionApp\nClass-Path: lib/appframework-1.03.jar lib/swing-worker-1.1.jar lib/AbsoluteLayout-RELEASE126.jar\n' \
    > build/classes/META-INF/MANIFEST.MF

cp lib/*.jar dist/lib/
rm -f dist/CacheModeler_MultyprocessorVersion.jar
(cd build/classes && zip -qr ../../dist/CacheModeler_MultyprocessorVersion.jar META-INF cachemodeler_multyprocessor)

echo "Built dist/CacheModeler_MultyprocessorVersion.jar"

#!/usr/bin/env bash
# Tests sans serveur Minecraft : simulation des stats + API (auth, rôles, force brute, console).
# Écrit ../site/stats.sample.json. Usage : test.sh [sortie.json]
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
rm -rf build/test-classes && mkdir -p build/test-classes
CP="build/classes$(printf ';')lib/log4j-api-2.19.0.jar$(printf ';')lib/log4j-core-2.19.0.jar"
javac -proc:none --release 17 -encoding UTF-8 -cp "$CP" -d build/test-classes $(find src/test/java -name '*.java')
RUN="$CP$(printf ';')build/test-classes"
java -Dstdout.encoding=UTF-8 -cp "$RUN" fr.aethoria.stats.SampleGen "${1:-../site/stats.sample.json}"
java -Dstdout.encoding=UTF-8 -cp "$RUN" fr.aethoria.stats.ApiTest

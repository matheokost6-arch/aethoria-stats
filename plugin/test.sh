#!/usr/bin/env bash
# Test sans serveur : simulation, sérialiseur, persistance. Écrit ../site/stats.sample.json.
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
rm -rf build/test-classes && mkdir -p build/test-classes
javac --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes $(find src/test/java -name '*.java')
java -cp "build/classes$(printf '\;')build/test-classes" fr.aethoria.stats.SampleGen "${1:-../site/stats.sample.json}"

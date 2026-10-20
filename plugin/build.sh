#!/usr/bin/env bash
# Compile plugin/src en Java 17 et crée plugin/build/AethoriaStats.jar.
# Besoin : un JDK 17 ou plus, et plugin/lib/spigot-api.jar (voir README).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
javac --release 17 -encoding UTF-8 -cp lib/spigot-api.jar -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
(cd build/classes && jar --create --file ../AethoriaStats.jar .)
echo "OK : build/AethoriaStats.jar"

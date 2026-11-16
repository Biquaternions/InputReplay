#!/usr/bin/env bash
# Starts a local 26.3 test server for InputReplay under run/server/ (flat, no mobs, offline mode).
# Uses the server jar from the Loom cache unless SERVER_JAR is set.
# Running it means you accept the Minecraft EULA (https://aka.ms/MinecraftEULA).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/run/server"
JAR="${SERVER_JAR:-$HOME/.gradle/caches/fabric-loom/26.3/minecraft-server.jar}"
mkdir -p "$DIR"
cd "$DIR"
[ -f eula.txt ] || echo "eula=true" > eula.txt
if [ ! -f server.properties ]; then
  cat > server.properties <<'EOF'
level-type=minecraft\:flat
spawn-monsters=false
spawn-animals=false
generate-structures=false
online-mode=false
view-distance=4
simulation-distance=4
difficulty=peaceful
gamemode=creative
spawn-protection=0
server-port=25565
motd=InputReplay test server
EOF
fi
exec java -Xmx2G -jar "$JAR" nogui "$@"

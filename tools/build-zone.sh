#!/usr/bin/env bash
# Builds the three offline packs for one zone listed in tools/zones.json:
#   <id>-routing.tar    Valhalla tile extract (on-device routing, same Valhalla version as valhalla-mobile)
#   <id>-map.pmtiles    OpenMapTiles vector map, rider-focused (no buildings/house numbers)
#   <id>-search.sqlite  offline place search
# Requires: docker, java 21, jq, curl, and tools/packbuilder installed (gradle :tools:packbuilder:installDist).
# Usage: tools/build-zone.sh in-western [outdir]
set -euo pipefail

ZONE="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mkdir -p "${2:-$ROOT/packs-build/out}" && cd "${2:-$ROOT/packs-build/out}" && pwd)"
WORK="$ROOT/packs-build/work-$ZONE"
VALHALLA_IMAGE="ghcr.io/valhalla/valhalla:3.9.0"   # must match valhalla-mobile's bundled core
PLANETILER_JAR="${PLANETILER_JAR:-$ROOT/packs-build/planetiler.jar}"
PACKBUILDER="$ROOT/tools/packbuilder/build/install/packbuilder/bin/packbuilder"
mkdir -p "$WORK"

PBF_URL=$(jq -r --arg z "$ZONE" '.[] | select(.id == $z) | .pbf' "$ROOT/tools/zones.json")
POLY_URL=$(jq -r --arg z "$ZONE" '.[] | select(.id == $z) | .poly' "$ROOT/tools/zones.json")
[ "$PBF_URL" != "null" ] || { echo "unknown zone $ZONE"; exit 1; }

echo "== download $ZONE"
curl -sSfL -o "$WORK/$ZONE.osm.pbf" "$PBF_URL"
curl -sSfL -o "$WORK/$ZONE.poly" "$POLY_URL"
"$PACKBUILDER" bbox "$WORK/$ZONE.poly" > "$OUT/$ZONE.bbox"

echo "== routing (Valhalla)"
docker run --rm -v "$WORK:/data" "$VALHALLA_IMAGE" bash -c "
  set -e
  cd /data
  valhalla_build_config --mjolnir-tile-dir /data/tiles --mjolnir-tile-extract /data/routing.tar \
    --mjolnir-timezone /data/timezones.sqlite --mjolnir-admin /data/admins.sqlite > valhalla.json
  valhalla_build_timezones > timezones.sqlite || rm -f timezones.sqlite
  valhalla_build_admins -c valhalla.json $ZONE.osm.pbf
  valhalla_build_tiles -c valhalla.json $ZONE.osm.pbf
  valhalla_build_extract -c valhalla.json -v
"
sudo chown -R "$(id -u):$(id -g)" "$WORK" 2>/dev/null || true
mv "$WORK/routing.tar" "$OUT/$ZONE-routing.tar"

echo "== map (Planetiler)"
java -Xmx6g -jar "$PLANETILER_JAR" --osm-path="$WORK/$ZONE.osm.pbf" --download --download-dir="$ROOT/packs-build/sources" \
  --tmpdir="$WORK/tmp" --output="$OUT/$ZONE-map.pmtiles" --exclude_layers=building,housenumber --force

echo "== search"
JAVA_OPTS=-Xmx6g "$PACKBUILDER" search "$WORK/$ZONE.osm.pbf" "$OUT/$ZONE-search.sqlite"

ls -la "$OUT"

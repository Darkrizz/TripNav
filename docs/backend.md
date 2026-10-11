# Routing backend

TripNav calls a [Valhalla](https://github.com/valhalla/valhalla) server for routes (`/route`) and speed limits (`/trace_attributes`). The server URL can be changed under **Settings → Routing server**.

## Development
The default is the public FOSSGIS instance `https://valhalla1.openstreetmap.de`. It is rate-limited and meant for light use only. Don't ship a production app against it.

## Self-hosting (recommended for production)
```bash
# Example: a single country extract on a 4 GB VPS
mkdir -p valhalla/custom_files && cd valhalla
wget -O custom_files/region.osm.pbf https://download.geofabrik.de/asia/india-latest.osm.pbf
docker run -d --name valhalla -p 8002:8002 \
  -v $PWD/custom_files:/custom_files \
  -e tile_urls=  -e serve_tiles=True -e build_admins=True -e build_time_zones=True \
  ghcr.io/gis-ops/docker-valhalla/valhalla:latest
```
Put the server behind HTTPS (e.g. Caddy) and enter `https://your-host` in TripNav.

## Other services
| Purpose | Service | Policy |
|---|---|---|
| Map tiles/styles | OpenFreeMap (`tiles.openfreemap.org`) | Free, no key |
| Search | Photon (`photon.komoot.io`) | Fair use; self-host for volume |
| Reverse geocoding | Nominatim (`nominatim.openstreetmap.org`) | Max 1 req/s, a valid User-Agent is required (TripNav sends one) |

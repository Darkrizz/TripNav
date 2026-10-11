# TripNav

TripNav is a motorcycle navigation app for Android that drives the **TripperPod** BLE handlebar display (`RE_DISP`). It is a clean-room successor to *Super TripperPod* v4.1. The pod protocol was recovered by static analysis (`docs/tripper-protocol.md`). No code, assets, keys or backend from the original are used.

## What it does
- **Pod link.** Pairs through the system companion-device picker or a scan, with 6-digit PIN pairing. A 1 Hz keepalive holds the pod screen. Night mode, clock sync, firmware and serial readout. Auto-reconnect for 10 minutes after a drop. Shows a call screen during phone or VoIP calls.
- **Built-in turn-by-turn.** Valhalla motorcycle routing (fastest, avoid motorways, or curvy roads; avoid tolls or ferries). Multi-stop routes, off-route detection with automatic rerouting, and precise maneuver icons on the pod including roundabout exit numbers.
- **Pod display options.** Arrival time, time left, distance left or next-turn distance. Compass or clock when idle. Night mode follows real sunset and sunrise at your position.
- **Google Maps mirroring.** If you navigate in Google Maps instead, its turns are shown on the pod. Parsing is on-device and multilingual, and you can teach TripNav any arrow it doesn't recognise.
- **Voice guidance.** Ducks music, with optional street names.
- **Speed limits.** OSM speed limits on screen, with a configurable over-speed warning.
- **Search.** Photon (typo-tolerant, location-biased), favourites and recents, long-press to drop a pin. Also handles `geo:` links and places shared from Google Maps or any app (coordinates and short links are resolved on-device).
- **Trips.** Automatic ride recording (distance, moving time, average and max speed), GPX export, and riding a GPX file as a route.
- **Developer tools.** Raw frame console with live TX/RX log, pod icon tester, route simulator.

## Improvements over Super TripperPod
| Area | Super TripperPod | TripNav |
|---|---|---|
| Turn source | Scraped Google Maps icons matched against a server catalogue | Structured maneuvers from its own router; Maps mirroring as a fallback, processed fully on-device |
| Privacy | Unknown icons, diagnostics and usage uploaded to the developer's server | Nothing leaves the phone except routing and search queries |
| Permissions | Phone state, storage, delete packages, background location | No phone-state, storage or background-location permissions |
| Background | OEM battery-whitelist workarounds | Companion-device association plus a typed foreground service |
| Night mode | Fixed schedule | Real sunrise and sunset at your location |
| Codebase | One 4,000+ line activity, 3 HTTP stacks | Modular Kotlin/Compose app; protocol is a pure module with tests |

## Build
Needs JDK 17–21 and the Android SDK (platform 36).
```bash
./gradlew :core:protocol:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                          # minified, debug-signed for side-loading
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First ride checklist
1. Open TripNav and grant location, nearby devices and notifications.
2. Go to **Pod**, tap **Pair**, pick `RE_DISP`, then type the PIN the pod shows.
3. In **Pod → Test arrow**, the pod should show a right turn, 150 m.
4. In **Ride**, search for a destination, tap **Ride** and go.
5. Optional: in **Pod → Grant notification access**, allow Google Maps mirroring.

## Layout
```
core/protocol   Pure-Kotlin TripperPod codec (frames, CRC, icons, distance/ETA encoding) + tests
app/            Android app
  ble/          PodConnection (GATT, PIN, queue, keepalive, reconnect), PodScanner (scan + CDM)
  nav/          ValhallaClient, RouteFollower, NavEngine, ManeuverMapper, VoiceGuide
  pod/          PodPresenter: decides what the pod shows (nav > Maps mirror > compass/clock)
  mirror/       Google Maps notification listener, text parser, icon hashing/learning
  search/ trips/ data/ settings/ location/ service/ ui/
docs/           Protocol spec, backend notes
```

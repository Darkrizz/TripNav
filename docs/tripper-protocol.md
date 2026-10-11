# TripperPod BLE protocol

This spec was recovered by static analysis of `Super TripperPod.apk` v4.1. The sources were `TripperProtocol`, `TripperBleManager`, `InternalMapManager.sendManeuverToTripper` and `IconTesterScreen`. TripNav implements it clean-room in `core/protocol`.

## Transport
| Item | Value |
|---|---|
| Advertised name | `RE_DISP` |
| GATT service | `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0` |
| Characteristic (write + notify) | `01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb`: write `ENABLE_NOTIFICATION_VALUE` |
| Write type | `WRITE_TYPE_NO_RESPONSE` (1). One write in flight; the next one goes 80 ms later |
| Connect | `connectGatt(ctx, autoConnect=false, cb, TRANSPORT_LE)`, then `discoverServices()` |
| Phone-side GATT server | The original app also opens a GATT server exposing the same service and characteristic (props WRITE\|WRITE_NR\|NOTIFY = 0x1C... created as `(uuid, 12, 16)`). The pod may write responses there. TripNav mirrors this. |
| Bonding | None. Auth is the app-level PIN below. |

## Frame
Every frame is **20 bytes**. Bytes `[18..19]` = CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`, no reflection, no xor-out) over bytes `[0..17]`, **big-endian**.

## Commands (byte 0)
| Op | Name | Payload |
|---|---|---|
| `0x03` | PING_FW | zeros. Reply `03 MM mm …`: firmware BCD `MM.mm` |
| `0x10` | NAVIGATE | see below. Reply `0x10`: NAV_ACK |
| `0x20` | PIN | bytes 1..6 = 6 ASCII digits. Reply `20 01` = accepted, `20 00` = rejected |
| `0x21` | SESSION | `21 01` = show the PIN on the pod; `21 00` = close/resume session |
| `0x30` | PING_WP | zeros. Reply `30` + up to 7 ASCII bytes = serial |
| `0x40` | CONTROL | `40 01` nav started, `40 05` nav stopped / call icon |
| `0x50` | SET_TIME | b1 = hour (24 h), or `(1=AM\|2=PM)<<6 \| hour12`; b2 = minute. Reply `0x50` |
| `0x02` | (reply) | NACK |

Fixed frames (CRC included):
```
PIN_SHOW  21010000000000000000000000000000000050A7
CLOSE     2100000000000000000000000000000000004045
PING_FW   03000000000000000000000000000000000045D9
PING_WP   300000000000000000000000000000000000428B
STOP_NAV  10111C00000100FF00000000000000000000F782
NAV_IDLE  10113C0000044015000041000403000000001050
```

## Navigate frame (`10 11 …`)
| Byte | Meaning |
|---|---|
| 0–1 | `10 11` |
| 2 | **Main icon ID** (table below) |
| 3–4 | Distance to the maneuver, uint16 BE, in the unit from byte 5 |
| 5 | Distance unit: `1` m (≤999), `2` hundreds of metres (km×10), `3` tenths of a mile, `4` feet (≤550) |
| 6 | Proximity bar: `0` >95 m, `16` ≤95, `32` ≤70, `48` ≤45, `64` ≤20, `80` ≤10. **Bit 0 = night mode** |
| 7 | Next-maneuver icon ID, or `60` (0x3C) when byte 11–13 carry a speed-camera distance |
| 8–9 | `FF FF` |
| 10 | `0x41` |
| 11–13 | Secondary field. One of: distance (hi, lo, unit) for the next step / remaining trip / speed camera; or ETA `HH MM 00` (duration, or arrival clock, with AM/PM encoded as hour+64 AM, hour+128 PM); or `FF FF FF` = none |
| 14–17 | `00` |
| 18–19 | CRC |

Special screens use the same frame:
- **Compass:** b2=`0x41`, b6=night, b7=`FF`, b11–13=`FF`, b14 = direction.
- **Compass direction byte** for N, NE, E, SE, S, SW, W, NW = `0x10, 0x50, 0x20, 0x70, 0x40, 0x80, 0x30, 0x60`.
- **Loading:** b2=`0x1C`, the rest 0 (+ night bit).
- **Locked/no-data:** b2=`0x42`, b3..17=`FF`.

## Icon IDs
```
0 DESTINATION   1 DEST_LEFT   2 DEST_RIGHT   3 MERGE_RIGHT   4 MERGE_LEFT   5 FORK_RIGHT   6 FORK_LEFT
7 OFF_RAMP_RIGHT   8 OFF_RAMP_LEFT   9 STRAIGHT
10 ROUNDABOUT_CW base  (11..19 = clockwise exit 1..9, left-hand traffic)
20 TURN_LEFT  21 TURN_RIGHT  22 SHARP_LEFT  23 SHARP_RIGHT  24 SLIGHT_LEFT  25 SLIGHT_RIGHT
26 U_TURN_CW (LHT)  27 MERGE  28 REROUTE
29 ON_RAMP_RIGHT 30 ON_RAMP_LEFT 31 ON_RAMP_SLIGHT_RIGHT 32 ON_RAMP_SLIGHT_LEFT 33 ON_RAMP_SHARP_RIGHT 34 ON_RAMP_SHARP_LEFT
39 KEEP_RIGHT* 40 KEEP_LEFT* 41 RAMP_RIGHT* 42 RAMP_LEFT*  (*used by the original's router)
43 KEEP_LEFT 44 KEEP_RIGHT 45 OFF_RAMP_SLIGHT_RIGHT 46 OFF_RAMP_SLIGHT_LEFT 47 OFF_RAMP_SHARP_RIGHT 48 OFF_RAMP_SHARP_LEFT
49 ROUNDABOUT_CCW base (50..58 = counter-clockwise exit 1..9, right-hand traffic)
60 DEPART  61 U_TURN_CCW (RHT)  62 FERRY  63 FERRY_TRAIN  65 COMPASS  66 NO_DATA  68 LOW_BATTERY
```

## Session flow
1. Connect, then discover services, enable notifications, and wait 200 ms.
2. If the pod is **known** (it accepted a PIN earlier), send `CLOSE`, then after 200 ms `SET_TIME`, then after 150 ms `PING_FW`×2. The session is ready.
3. If the pod is **new**, send `PIN_SHOW`. The pod displays a 6-digit PIN and the user types it in. The app sends `PIN(code)`, then `SET_TIME` (150 ms), `PING_FW` (300 ms), and `PING_WP` (500/600 ms). On `20 01` the pod is stored as known and the session is ready.
4. On ready, start the keepalive and send the loading or compass screen.
5. **Keepalive (1 Hz):** resend the last navigation frame. In clock mode, send `PING_FW` every 15 s instead. While a call is active, send `40 05`.
6. **Disconnect:** send `CLOSE`, then after 300 ms `gatt.disconnect()`.
7. **Reconnect:** retry every 5 s, giving up after 10 min.

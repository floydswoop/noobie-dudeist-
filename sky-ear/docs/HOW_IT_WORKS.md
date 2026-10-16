# How Sky Ear works

## Architecture

```
 +-------------------------------+         +--------------------------------------+
 |  Page  (assets/index.html)    |         |  Android code  (MainActivity.kt)     |
 |  HTML + CSS + JavaScript      |         |                                      |
 |  - synth, quiz, library UI    | calls   |  - microphone recording to WAV       |
 |  - Web Audio sound engine     | ------> |  - location, ADS-B requests          |
 |  - sound analysis (FFT)       |  Bridge |  - Bluetooth and Wi-Fi scanning      |
 |  - Remote ID decoding         |         |  - rotation sensor                   |
 |  - aircraft matching          | <------ |  - file storage and export           |
 |                               | onXxx() |  - serves saved clips to the page    |
 +-------------------------------+         +--------------------------------------+
```

The page runs in a `WebView` loaded with the base address `https://skyear.local/`. That address is never contacted; it only makes the page a secure context, which microphone access requires. A request handler in Kotlin answers `https://skyear.local/clips/<id>.wav` and `https://skyear.local/picked` from local files, so the page can play and analyze recordings. All other navigation is blocked.

Most logic lives in the page on purpose: it can be tested with Node.js (`tests/run-tests.js`) and edited without rebuilding Kotlin.

### Calls from the page into Android (`Android.*`)

| Method | Purpose |
|---|---|
| `requestOverhead(radiusNm)` | Get location, fetch nearby aircraft |
| `startRecording(radiusNm)` / `stopRecording()` | Record audio with aircraft snapshots and orientation |
| `startRid()` / `stopRid()` | Start or stop Remote ID scanning |
| `pickAudio()` | Open the system file picker for "Analyze a recording" |
| `listClips()`, `getClip(id)`, `saveClip(id, json)`, `deleteClip(id)` | Library storage |
| `exportClip(id)` | Copy a clip's WAV and JSON to Downloads/SkyEar (Android 10+) |
| `getPref(key)` / `setPref(key, value)` | Small remembered settings |

### Calls from Android into the page

`onOverhead(json)`, `onOverheadError(msg)`, `onRecState(msg)`, `onRecLevel(peak)`, `onClipSaved(id)`, `onOrient(json)`, `onOrientError(msg)`, `onRidState(msg)`, `onRidBatch(json)`, `onPicked(status)`, `onExport(msg)`.

## Storage

Clips are saved in the app's private folder `files/clips/`: `clip_YYYYMMDD_HHMMSS.wav` (mono, 16-bit, 44.1 kHz) and a matching `.json`.

```
{
  "id", "startMs", "durationMs", "sampleRate", "source", "note", "radiusNm",
  "lat", "lon", "accM",                      // phone location (absent if unavailable)
  "label", "dtype",                          // set in the Library: aircraft | drone | other
  "snaps":  [ { "t", "ac": [ {hex, flight, type, reg, altFt, gs, track, lat, lon, distM, bearing} ] } ],
  "analysis":   { "f0", "count", "slope", "cl", "match": [preset names] } | { "quiet": 1 },
  "candidates": [ { hex, flight, type, reg, minR, atS, el, altFt, gs, aimErr } ],
  "rid":   [ { "t", "u": [ {id, addr, uaType, lat, lon, height, speed, rssi} ] } ],
  "point": { "decl", "mode", "samples": [ {t, azm, el, azm2, el2, acc} ] }
}
```

## The sound model (Synth)

Each motor is one oscillator. Its fundamental is the **blade-pass frequency**:

`f = blades x RPM / 60`, with RPM reduced by 12% x load, and each motor's RPM offset by a fixed random amount (the mismatch) so motors beat against each other. Harmonic *k* has amplitude `rolloff^(k-1)`. A second oscillator adds motor whine at about 12 x f (capped at 9 kHz), air noise is band-passed white noise, and a slow wobble modulates pitch. Distance sets the level (`min(1, 10/r)`) and a low-pass filter (`14000 / (1 + r/20)` Hz). The fly-by moves a source past you at 18 m/s with a Doppler shift.

The library of drone and lookalike presets is a table of these parameters (`LIB` in the script). **They are estimates**, not measurements.

## Sound analysis (Listen, Library)

1. Average the power spectra of 8192-point FFTs (Hann window) over the louder half of the recording.
2. Search 40 to 1500 Hz for the fundamental whose first six harmonics stand highest above the median noise floor, then refine it by parabolic interpolation.
3. Count harmonics (of the first 8) that are at least 12 dB above the floor, fit the dB-per-harmonic slope, and count close peaks near the fundamental (several motors beating).
4. Rank presets by `cents/150 + |slope difference|/6 + 0.1 x |harmonic count difference|`.

## Matching a clip to aircraft

During a recording the app queries the ADS-B service at the start, every 15 s, and at the end. Afterwards, for every second of the clip and each aircraft, the page:

1. dead-reckons the aircraft between snapshots using ground speed and track;
2. finds where it was when the sound you hear left it (distance / 343 m/s earlier);
3. records the closest slant range and the time of closest approach.

The five closest become the "likely sources". If pointing data exists, it also computes the median angle between where the phone pointed and where each aircraft was.

## Pointing

The Android rotation-vector sensor gives a 3x3 matrix `R` that turns phone axes into east/north/up. The back of the phone points along `(-R[2], -R[5], -R[8])` and the top edge along `(R[1], R[4], R[7])`. Compass bearing is `atan2(east, north)` plus the magnetic declination for your location; elevation is `asin(up)`. Both aim modes are stored every half second.

## Remote ID

- **Bluetooth:** advertisements carrying service data for UUID `0xFFFA`, first byte `0x0D`, then a counter and either one 25-byte message or a message pack.
- **Wi-Fi Beacon:** a vendor-specific element with OUI `FA:0B:BC`, type `0x0D`, then a counter and a message pack (Android 11+; Android throttles scans).
- **Messages (25 bytes each):** type in the high nibble of byte 0: 0 basic ID, 1 location/vector, 3 self-ID, 4 system (operator position), 5 operator ID, 0xF message pack.
- Location values are 32-bit integers in units of 1e-7 degrees; altitudes are `value x 0.5 - 1000` meters; speed is `value x 0.25` m/s (with a multiplier flag for high speeds).

Kotlin only forwards the raw bytes as hex. All decoding is in the page, which is why it can be tested against messages produced by the reference library.

## Limitations of the design

The app relies on one microphone and cannot determine direction from sound. All matching is approximate and uses public, unverified data. See the README for the full list.

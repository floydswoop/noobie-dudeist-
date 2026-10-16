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

## Combined scan (Field tab)

The **Start scan** button runs three things together and shows one readout (Sound, Aircraft, Drone signals):

1. **Microphone.** `getUserMedia` with echo cancellation, noise suppression and auto gain turned off, an 8192-point analyser, and the same `analyze()` used by the Listen tab, about every 0.7 s.
2. **Aircraft.** `Android.requestOverhead()` immediately and then every 15 s, using the search radius slider. Answers arrive through `onOverhead` / `onOverheadError`.
3. **Drone signals.** `Android.startRid()`. If Android pauses scanning (for example while the app is in the background) the 15-second check starts it again, unless you stopped it by hand or Android reported an error such as a denied permission.

The steps start one after another, because Android shows one permission prompt at a time: microphone, then location, then Bluetooth and nearby devices. A failure in one step (for example a denied microphone) does not stop the others.

Starting a recording while the scan runs releases the scan's microphone and pauses aircraft checks, since the recording takes its own aircraft snapshots; Remote ID scanning continues and is logged into the clip. The Field tab shows a dot while the scan runs.

`cbSummary()` turns the latest readings into the three text lines and has no browser dependencies, so it is covered by `tests/run-tests.js`.

## Limitations of the design

The app relies on one microphone and cannot determine direction from sound. All matching is approximate and uses public, unverified data. See the README for the full list.

## Radar (Field tab)

Under the search radius slider, a radar plots the aircraft from the latest ADS-B reply with the phone at the centre. Range rings are a third of the search radius each. Each aircraft is moved along its reported track and ground speed for the seconds since the reply (up to 2 minutes), so it keeps drifting between 15 s updates. The shaded wedge is the direction the phone points (+/-15 degrees), using the same "Aim with" setting and compass data as recording. Aircraft inside the wedge are highlighted and named under the radar. A button switches between pointing-up (the picture turns with you) and north-up. The compass streams only while the Field tab is open, to save battery. The wedge uses magnetic north until a location fix gives the declination (the first ADS-B request supplies it). The wedge compares bearing only, not elevation.

### Contact details, ADS-B age and sound delay

Tap an aircraft on the radar (or a row in the list) to open a detail card. Two delays are allowed for:

- **ADS-B age.** Each aircraft's position is moved along its reported track and ground speed for its total age: the `seen_pos` the data service reports (how old the position was when sent), plus the time since the reply arrived, plus 1 s for the service itself. Reports are capped at 120 s of extrapolation.
- **Sound delay.** The sound you hear now left the aircraft `tau` seconds ago, where `tau` = straight-line distance (ground distance and height) divided by the speed of sound. That distance depends on where the aircraft was, so the app solves it by iteration. The card shows *Look at* (where the aircraft is now) and *Sound from* (where it was `tau` seconds ago), each with how far to turn and tilt the phone. The beam highlight on the radar uses the sound position, because that is where you are listening toward.

The speed of sound is fixed at 343 m/s (about 20 C) and shared with the recording matcher. Flight is assumed straight and steady, so a turning aircraft will drift from the estimate, and a 1 to 2 s timing error is roughly 50 to 100 m for a slow plane. The maths is in `rdSolve` and is covered by tests.

## Weather (Field tab)

The Android app asks Open-Meteo for current conditions at your location (rounded to about 1 km, cached for 10 minutes) whenever a scan or recording needs it. The reply is optional: if it fails, everything else works.

- **Speed of sound.** Air temperature sets it: `c = 331.3 * sqrt(1 + T/273.15)` m/s, so 15 C gives 340 m/s and -10 C gives 325 m/s. The radar's sound delay and the recording matcher use this value instead of a fixed 343 m/s. Humidity changes it by well under 0.5 percent and is ignored. Wind is not added to the path.
- **Weather card.** Temperature, wind (with gusts and the direction it blows from), cloud, visibility, rain and pressure, with short listening advice: wind of about 5 m/s or more adds microphone noise; sounds from the side the wind blows from carry farther to you.
- **Clips.** The weather at the start of a recording is saved in the clip's details and shown in the Library. Matching a saved clip uses that clip's own temperature.

## Accessibility

- Tabs, panels, sliders and the aircraft list use standard roles and labels. Aircraft rows are focusable buttons that open the details card and move focus to it.
- The radar and spectrograms are pictures, so each has a text description. The radar's description lists the nearest aircraft with distance and direction and is updated about once a second.
- A polite live region announces scan start and stop, the nearest aircraft when it changes, drone signals when the count changes, and recording events. Repeats of the same message are suppressed so a 15-second scan does not read the same sentence again.
- The first-run guide is a modal dialog: focus moves into it, Tab stays inside, Escape closes it, and focus returns to where it was. The page behind it is inert while it is open. The Help button reopens it.

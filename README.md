# Sky Ear

An Android app for learning what drones and aircraft sound like, and for building a library of labeled recordings.

Sky Ear started as a drone-safety idea: help people recognize the sound of different drones, and tell them apart from the many things that sound similar (leaf blowers, mopeds, insects, small planes). It has since grown into a small field kit:

- **Synth.** Builds drone and lookalike sounds from adjustable parameters (blade count, motor count, RPM, load, distance, Doppler fly-by). Includes a library of 15 drone presets and 10 non-drone lookalikes.
- **Quiz.** Plays hidden sounds and scores you on drone vs. not-a-drone and on drone type. Reports hits, false alarms and d-prime. Can use the synthesized sounds, your own labeled recordings, or both.
- **Listen.** Analyzes a live microphone feed or a saved recording: spectrogram, fundamental pitch, harmonics, and the closest synth presets.
- **Field** (the first tab).
  - *Start scan:* one button that listens through the microphone, checks ADS-B for nearby aircraft every 15 seconds, and scans for Remote ID drone broadcasts, with one combined readout.
  - *What's overhead:* lists nearby aircraft from public ADS-B data (type, distance, direction, altitude, angle above the horizon).
  - *Remote ID:* scans Bluetooth and Wi-Fi for drones broadcasting the standard Remote ID signal.
  - *Record a clip:* records audio, and at the same time logs nearby aircraft, Remote ID drones, and where the phone was pointing (compass direction and tilt).
- **Library.** Stores your clips with analysis, likely source aircraft (allowing for the delay of sound travel), pointing data, labels and notes. Clips can be exported to Downloads, and a clip's measured sound can be copied into the synth.

> **Status: experimental (version 0.1).** It was developed and field-tested on a single Android phone. It is a learning and research tool. It is **not** a drone detector, a safety system, or suitable for navigation or any safety-critical use. The brand-and-model presets are estimates from typical specifications, not measurements, and will not exactly match real recordings.

## Requirements

- An Android phone (Android 8 or newer recommended; Remote ID over Wi-Fi needs Android 11 or newer).
- To build it: a computer with [Android Studio](https://developer.android.com/studio) (free).
- Optional: headphones (phone speakers cannot reproduce low tones), and a drone that broadcasts Remote ID if you want to test that feature.

## Build it

The full, beginner-friendly walkthrough is in **[docs/BUILDING.md](docs/BUILDING.md)**. In short:

1. Create a new **Empty Activity** project in Android Studio named **Sky Ear** (keep the default package `com.example.skyear`).
2. Copy the files from this repository's `app/src/main` folder over the matching files in your project.
3. Press Run with your phone connected.

The whole user interface is one file, `app/src/main/assets/index.html`, so you can change colors, text and layout without touching Kotlin.

## Permissions

| Permission | Why |
|---|---|
| Microphone | Listening mode and recording clips |
| Location | Finds nearby aircraft, tags clips, and is required by Android for Bluetooth and Wi-Fi scanning |
| Internet | Asks the ADS-B service for nearby aircraft (nothing else) |
| Bluetooth scan / Nearby devices / Wi-Fi state | Scanning for Remote ID broadcasts |

Everything else (recordings, labels, settings) stays on the phone. See **[docs/PRIVACY.md](docs/PRIVACY.md)**.

## Documentation

- [docs/BUILDING.md](docs/BUILDING.md): step-by-step build and troubleshooting
- [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md): architecture, algorithms, data formats
- [docs/PRIVACY.md](docs/PRIVACY.md): what is stored, what leaves the phone
- [CONTRIBUTING.md](CONTRIBUTING.md): how to help, and how to run the tests

## Tests

The sound analysis, Remote ID decoder, pointing math and aircraft matching have automated tests that run with Node.js and need no phone:

```
node tests/run-tests.js
```

The Remote ID test messages were generated with the reference Open Drone ID library, so the decoder is checked against real encoder output. The phone-specific code (Bluetooth and Wi-Fi scanning, microphone, sensors, location) cannot be tested this way and has only been tried by hand.

## Known limitations

- Remote ID shows only drones that broadcast it, and only within roughly a few hundred meters. Wi-Fi Beacon detection is slow because Android limits Wi-Fi scans, and Wi-Fi NAN broadcasts are not supported. Long-range Bluetooth 5 depends on the phone.
- Aircraft matching assumes straight flight between snapshots taken about 15 seconds apart. Treat the top match as likely, not certain.
- The compass can be thrown off by metal and magnets. The app warns when the sensor reports low accuracy.
- Scanning and recording work only while the app is on screen.
- Only the single-channel built-in microphone is used; the app does not do beamforming or localization.

## Ideas for the future

Auto-start recording when an aircraft comes near; bulk export; a standalone Gradle project that builds with a single command; a proper launcher icon; more drone and lookalike presets calibrated from real recordings; translations.

## Credits and notices

- Aircraft data comes from the community [adsb.lol](https://adsb.lol) public API. Its terms may change (it has announced that rate limits or an API key could be introduced), so check their documentation. The code that talks to the service is the `queryAircraft` function in `MainActivity.kt` and is easy to swap for another provider.
- Remote ID decoding follows the ASTM F3411 message layout, as implemented in the [Open Drone ID](https://github.com/opendroneid) reference code (Apache-2.0). No code was copied; the reference library was used to generate test messages.
- Drone brand and model names (DJI, Autel, Parrot, Skydio and others) are trademarks of their owners and are used only to describe approximate sound characteristics. This project is not affiliated with or endorsed by them.
- Recording sound can capture people's voices. Know and follow the recording laws where you are.

## License

[MIT](LICENSE).

# Building Sky Ear

This guide assumes no Android experience. Allow about an hour the first time, mostly waiting for downloads.

Sky Ear is a small Android app that shows a web page (the whole interface) and adds the phone features a web page cannot reach: microphone, location, aircraft data, Bluetooth and Wi-Fi scanning, and motion sensors.

## What you need

- A computer (Windows, macOS or Linux) with about 8 GB of RAM (16 GB is better) and 8 GB of free disk space.
- [Android Studio](https://developer.android.com/studio), free from Google. Install it with the default options; on first launch it downloads the Android tools.
- An Android phone and a USB cable that carries data (some cables only charge).

## 1. Prepare your phone

1. Open **Settings > About phone** and tap **Build number** 7 times. (On some phones it is under Software information.)
2. Open **Settings > Developer options** and switch on **USB debugging**.
3. Connect the phone to the computer. When the phone asks "Allow USB debugging?", tap **Allow**.

## 2. Create a blank project

1. In Android Studio choose **New Project > Empty Activity > Next**.
2. Set **Name** to `Sky Ear`. Leave the **Package name** as the default (`com.example.skyear`). Keep **Language: Kotlin**. A minimum SDK of API 24 or higher is fine.
3. Click **Finish** and wait for the bottom progress bar to stop.
4. Choose your phone from the device list at the top and press the green **Run** button. You should see "Hello Android" on the phone. This proves your tools work. If it doesn't, fix that first; it is not a Sky Ear problem.

## 3. Copy in the Sky Ear files

This repository's `app/src/main` folder mirrors the one in your project. Copy these four files:

| From this repository | To your project |
|---|---|
| `app/src/main/AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` (replace) |
| `app/src/main/res/values/strings.xml` | `app/src/main/res/values/strings.xml` (replace) |
| `app/src/main/assets/index.html` | `app/src/main/assets/index.html` (see below) |
| `app/src/main/java/com/example/skyear/MainActivity.kt` | `app/src/main/java/com/example/skyear/MainActivity.kt` (replace) |

Tips:
- **Replace a file completely:** open it in Android Studio, press Ctrl+A, Delete, then paste the new contents. Don't merge old and new text.
- **The `assets` folder doesn't exist yet.** Switch the left panel to **Android** view, right-click `app`, choose **New > Folder > Assets Folder**, accept the defaults, then put `index.html` inside. It must end up at `app/src/main/assets/index.html`.
- **If your package name differs** from `com.example.skyear`, change line 1 of `MainActivity.kt` to match your project's package. The manifest assumes your theme is named `Theme.SkyEar`; if Android Studio marks it red, use the theme name your project already had.
- **Permission lines belong inside `<manifest ...>` but outside any `<` `>` tag.** The most common mistake is pasting a `<uses-permission ... />` line in the middle of the opening tag.

## 4. Build and run

1. Choose **Build > Clean Project**.
2. Press the green **Run** button.
3. The first start asks for permissions as you use each feature: microphone, location, and nearby devices.

To check everything works: open the **Synth** tab and tap Start (use headphones), then try the **Field** tab's "What's overhead" button.

## Updating

Replace the changed files the same way, run **Build > Clean Project**, then Run. Keep the same package name each time: Android treats a different package as a different app, and your saved clips live inside the app.

## Troubleshooting

| What you see | Likely cause and fix |
|---|---|
| `Element type "manifest" must be followed by either attribute specifications...` | A permission line was pasted inside the opening `<manifest` tag. Put it after the `>` that closes that tag. |
| Red error `... overrides nothing` or `Unresolved reference` | A partly pasted or mixed file. Replace the whole file as described above. If it persists, open an issue with every line starting `e:` from the Build panel. |
| App closes at launch; Logcat says `FileNotFoundException: index.html` | `index.html` is not at `app/src/main/assets/index.html`, or has a different name such as `index (1).html`. |
| Overhead or Library say "Android app only" | The page is old or the Kotlin file is old. Replace both files and Clean Project. |
| Microphone message `NotAllowedError` | The manifest lacks `RECORD_AUDIO` / `MODIFY_AUDIO_SETTINGS`, or the permission was denied: Settings > Apps > Sky Ear > Permissions. |
| No location prompt | The manifest lacks the location permissions. Rebuild after fixing it. |
| Remote ID finds nothing | Turn on Bluetooth, Wi-Fi and Location. Only drones that broadcast Remote ID appear. Wi-Fi updates are slow (about every 30 seconds). |
| Compass reading looks wrong | Wave the phone in a figure 8 to calibrate; keep it away from metal and magnets. |
| "Export needs Android 10 or newer" | Exporting to Downloads uses a newer storage method. Use the clip files from the app's private storage instead. |

## Making a shareable APK

1. **Build > Generate Signed App Bundle / APK > APK**.
2. Create a keystore when asked. **Keep the keystore file and its passwords safe and private; never commit them to the repository.** You need the same keystore for every future update.
3. Copy the APK to a phone and open it. Android will ask you to allow installs from that source. Publishing on Google Play is a separate process with its own account requirements.

## Changing the interface

Everything you see is in `app/src/main/assets/index.html`. Colors are the `--bg`, `--fg`, `--ac` and `--card` values at the top. Text and buttons are in the middle. The long `<script>` section at the bottom holds the behavior. Keep a backup before editing, and you can preview the page in a desktop browser (phone-only features will say they need the app).

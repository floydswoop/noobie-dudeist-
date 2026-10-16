# Contributing to Sky Ear

Thanks for helping. Bug reports, field-test results and improvements are all welcome.

## Reporting a problem

Please include:
- phone model and Android version
- what you did, what you expected, and what happened
- for build errors: every line starting with `e:` from Android Studio's Build panel
- for crashes: the `FATAL EXCEPTION` lines from Logcat

**Before sharing an exported clip file (`clip_*.json`), remove the `lat`, `lon` and `snaps` fields.** They contain where you were.

## Sharing field-test results

The most useful reports are: whether the Library's "likely source" matched what you saw, what kind of aircraft or drone it was, and whether Remote ID detection worked with a particular drone model.

## Running the tests

```
node tests/run-tests.js
```

Please add a test when you change the sound analysis, the Remote ID decoder, the pointing math or the aircraft matching. Those all live in the `<script>` section of `app/src/main/assets/index.html`.

## Making changes

- The interface is plain HTML, CSS and JavaScript in `index.html`. There is no build step.
- The Android code is one file, `MainActivity.kt`. Anything the page needs from the phone goes through the `Bridge` class; see [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md).
- Keep changes small and say how you tested them.
- Do not add analytics, advertising, accounts or any network traffic beyond the ADS-B request. Privacy is a design goal.

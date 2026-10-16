# Privacy

Sky Ear has no accounts, no analytics, no advertising and no server of its own.

## What stays on your phone

- **Recordings** (`.wav`) and **clip details** (`.json`): saved in the app's private storage. They include the audio, your approximate location, the weather when you recorded, nearby aircraft lists, Remote ID drones heard, the direction the phone was pointing, your label and your note.
- **Settings** such as the "Aim with" choice.

Deleting a clip in the Library removes both its files. Uninstalling the app removes everything it stored. A clip only leaves the phone if you tap **Export to Downloads** and then share that file yourself.

## What leaves your phone

Only one thing: when you tap **What's overhead**, or while recording, the app asks the ADS-B service (`api.adsb.lol`) for aircraft near you. The request contains your **latitude and longitude** (as part of the web address) and a search radius. Like any web request, the service can also see your IP address. The app also asks the weather service Open-Meteo (`api.open-meteo.com`) for current conditions. That request contains your latitude and longitude **rounded to two decimals (about 1 km)**, and the service can see your IP address. Weather answers are reused for 10 minutes. Nothing else is sent to either service: no audio, no clips, no labels.

If that is not acceptable, do not use the Overhead feature or recording with aircraft tags. Everything else works without the internet.

## Remote ID

Remote ID signals are broadcasts that drones are required to make so they can be received nearby. The app displays what it hears, including the position of the operator or takeoff point if the drone broadcasts it. It **does not save the operator position** in clips. It does save each drone's ID and reported position in the clip's log, so treat exported clips as potentially identifying.

## Recording sound

Microphone recordings can contain voices and other private sounds. Only record where it is lawful and considerate, and delete clips you do not need.

## Permissions, in plain terms

Microphone for recording and listening. Location because the aircraft search needs it and because Android requires it for Bluetooth and Wi-Fi scanning. Internet for the aircraft and weather requests. Bluetooth and nearby-devices access for Remote ID scanning.

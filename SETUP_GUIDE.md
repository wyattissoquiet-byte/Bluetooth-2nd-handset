# Fresno Gateway — Setup and Pairing Guide

This guide walks through installing the app on the Hot Pepper Fresno, pairing it with your main phone, and getting your first call flowing. Total time is about ten minutes.

## What you need

You need the Hot Pepper Fresno flip phone running Android 12, the `app-release.apk` file from this repository's releases (or built from source), a USB cable or another way to get the APK onto the Fresno, and your main phone (any Android phone or iPhone with Bluetooth calling support).

## Step 1 — Install the APK on the Fresno

The Fresno runs full Android 12, so side-loading works the same as on any Android phone, just navigated with the D-pad.

**Via USB (recommended).** Enable Developer Options on the Fresno by opening *Settings → About phone* and pressing OK on *Build number* seven times. Then in *Settings → System → Developer options*, enable *USB debugging*. Connect the phone to a computer with `adb` installed and run:

```bash
adb install app-release.apk
```

**Via file transfer.** Alternatively, copy the APK to the phone over USB file transfer (MTP), then open it with the built-in Files app. When prompted, allow *Install unknown apps* for the Files app and confirm the install.

## Step 2 — Pair the two phones

Pairing happens once, in the system Bluetooth settings — the app then reuses the bond.

On the **Fresno**, open *Settings → Connected devices → Pair new device* so it becomes discoverable and starts scanning. On your **main phone**, open its Bluetooth settings, wait for "Fresno" (or the Fresno's Bluetooth name) to appear, and tap it. Confirm the 6-digit pairing code **on both phones**.

One detail matters here: because the app acts like a car kit, the *host* phone must allow the Fresno to use the phone-calls profile. On the main phone, open the gear/settings icon next to the paired Fresno entry and make sure **"Phone calls" (HFP)** is toggled on if the switch is shown. On iPhone this is automatic.

## Step 3 — Run the in-app setup wizard

Launch **Fresno Gateway** on the Fresno. The first run opens a six-step wizard; each screen has one action bound to the OK key, with a Skip button underneath.

| Step | What it grants | Why |
| --- | --- | --- |
| 1. Bluetooth | `BLUETOOTH_CONNECT` / `SCAN` runtime permissions | Talk to the host phone |
| 2. Phone | `CALL_PHONE`, `READ_PHONE_STATE`, microphone | Local SIM calls and audio |
| 3. Phone app | Default dialer role (optional) | Flip-friendly UI for the Fresno's own SIM calls |
| 4. Popups | Display over other apps | Incoming-call screen appears instantly |
| 5. Battery | Ignore battery optimizations | Keeps the Bluetooth link alive with the flip closed |
| 6. Host phone | Selects the paired device | Tells the gateway which phone to serve |

At step 6 the wizard opens the device picker: scroll to your main phone with the D-pad and press OK. The app immediately connects and the dashboard should show **"Link ready."**

## Step 4 — Test a call

Call your main phone from a third line. The Fresno should ring within a second, display the caller's number, and offer *Answer* / *Decline*. Try each of the answer methods: press the green call key, press D-pad OK, or — if the phone was closed — simply **open the flip**.

To dial out, press **2** on the dashboard (or the green call key), type a number on the keypad, and press OK. The call is placed on your main phone through the gateway link. During the call, the D-pad left toggles mute, right toggles speaker, digits send DTMF tones, and the red key hangs up. If you enabled *Hang up on flip close* in Settings, closing the phone ends the call.

## Audio modes — what to expect

The app always gives you full **call control** (ring, caller ID, answer, decline, hang up, dial, DTMF). Where the **voice audio** flows depends on the mode shown on the in-call screen:

| Mode | When active | Where the audio is |
| --- | --- | --- |
| Native HFP | Firmware has the Bluetooth HFP-client role enabled | Fresno's mic and earpiece — a complete handset experience |
| AT-command (fallback) | All other cases (default on stock retail builds) | Host phone (or its connected headset) |

This split exists because Android ships retail phones with the Hands-Free *client* role disabled in the Bluetooth stack; an ordinary app can exchange the HFP control protocol but cannot terminate the SCO voice link. The app automatically tries native mode first (toggle *Try native HFP audio* in Settings) and falls back gracefully. In fallback mode the practical pattern is: the Fresno is your ringer, caller-ID display, and remote control, while you speak through the main phone or a headset — or use the *Switch line* option in the dialer to place the call on the Fresno's own SIM instead, which always has full local audio.

## Troubleshooting

**"Connect failed" or the link drops.** Confirm the two phones are paired at the system level and within range, and that the "Phone calls" profile is enabled for the Fresno on the host phone's Bluetooth settings page. Toggling Bluetooth off and on at both ends resets a stuck bond.

**No ring on the Fresno.** Check that *Ring on this phone* is enabled in Settings, that the wizard's battery-optimization step was completed, and that the dashboard shows *Link ready* before the call arrives.

**Flip-open answer does not trigger.** The feature listens to the hall (lid) sensor while a ring is in progress. Verify *Answer on flip open* is enabled in Settings, and that the incoming-call screen appears when the phone rings (step 4 of the wizard grants the overlay permission this requires).

**The host is an iPhone and caller names are missing.** iPhones send the number via `+CLIP` but rarely a name; the Fresno then shows the number only. Numbers always come through.

**App vanished as default dialer after an update.** Re-run the setup wizard (dashboard option 4) and redo step 3.

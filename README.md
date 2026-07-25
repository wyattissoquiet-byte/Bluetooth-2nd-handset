# Fresno Gateway

**Fresno Gateway** turns a [Hot Pepper Fresno](https://www.phonescoop.com/phones/phone.php?p=7008) flip phone (Android 12) into a Bluetooth Hands-Free companion for your main phone. When your main phone rings, the Fresno rings too, shows the caller ID on its 2.8-inch screen, and lets you answer, decline, hang up, dial out, and send DTMF tones — all with the physical keypad, or simply by **opening the flip to answer** and closing it to hang up.

The app speaks the standard **Hands-Free Profile (HFP 1.7)** protocol directly, so the host phone needs **no companion app**: any Android phone or iPhone that supports Bluetooth calling with car kits and headsets will work.

## Features

| Feature | How it works |
| --- | --- |
| Ring on the Fresno | Local ringtone and vibration whenever the host phone gets a call |
| Caller ID | Number (and name, when the host provides one) shown full-screen |
| Answer / Decline / Hang up | Green key, red key, D-pad OK, or on-screen buttons |
| Answer on flip open | Opening the phone during a ring answers the call (toggle in Settings) |
| Hang up on flip close | Closing the phone during a call ends it (toggle in Settings) |
| Dial out via host | Type a number on the keypad; the call is placed on the host phone |
| DTMF tones | Press 0-9, *, # during a call to drive phone menus |
| Local SIM calls | Optional default-dialer role handles the Fresno's own SIM with the same flip-friendly UI |
| Auto-reconnect | Re-establishes the link after range drops or reboot |

## How it connects

The app implements the HFP **Hands-Free (HF) role** at the application layer: it opens an RFCOMM socket to the host's Handsfree Audio Gateway service (UUID `0000111F-...`), performs the standard Service Level Connection handshake (`AT+BRSF` → `AT+CIND` → `AT+CMER`), and then exchanges AT commands (`ATA`, `AT+CHUP`, `ATD`, `AT+VTS`, `+CLIP`, `+CIEV`, ...).

Two operating modes are attempted, in order:

1. **Native HFP mode** — the app binds the hidden `BluetoothHeadsetClient` system profile via reflection. On firmware where the HFP-client role is enabled, this provides full call control **and SCO audio routing**, meaning you talk and listen through the Fresno's own microphone and earpiece.
2. **AT-command mode (fallback)** — works on every device, providing ring alerts, caller ID, answer/decline/hang-up, dialing, redial, and DTMF. In this mode the **voice audio stays on the host phone** (or its headset), because retail Android builds disable the HF role in the Bluetooth stack and an unprivileged app cannot terminate the eSCO audio link.

The current mode is shown on the in-call screen; an "Audio → this phone" button requests an audio transfer when the native mode is active.

## Key map

| Key | Idle / Menu | Ringing | In call |
| --- | --- | --- | --- |
| D-pad OK / Green | Connect / select | Answer | — |
| Red end key / # | — | Decline | Hang up |
| 1 / 2 / 3 / 4 | Pair / Dialer / Settings / Setup | — | DTMF |
| D-pad left / right | navigate | — | Mute / Speaker |
| D-pad up | — | — | Pull audio to Fresno |
| Flip open | wake | Answer (optional) | — |
| Flip close | — | — | Hang up (optional) |

## Building

The project is a standard Gradle build (AGP 8.4, Kotlin 1.9, compileSdk 34, minSdk 31). Open it in Android Studio, or from the command line:

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

A development keystore is included at `keystore/fresno-gateway.jks` (alias `fresno`, password `fresno-gateway`) so the release build is installable out of the box. Replace it with your own keystore for anything beyond personal side-loading.

## Installing and pairing

See **[SETUP_GUIDE.md](SETUP_GUIDE.md)** for full step-by-step instructions covering side-loading the APK, pairing the two phones, the in-app setup wizard, and troubleshooting.

## Project layout

```
app/src/main/java/com/fresno/gateway/
├── GatewayApp.kt                 Application + notification channels
├── hfp/
│   ├── HfpAtEngine.kt            HFP HF-role AT-command engine over RFCOMM
│   ├── NativeHfpClient.kt        Hidden BluetoothHeadsetClient wrapper (reflection)
│   ├── HfpGatewayService.kt      Foreground service: link, ringer, flip gestures
│   ├── HfpModels.kt              LinkState / RemoteCallState / GatewayStatus
│   └── BootReceiver.kt           Reconnect after reboot
├── telecom/
│   ├── GatewayInCallService.kt   InCallService for local SIM calls
│   └── LocalCallManager.kt       Telecom call holder + controls
├── ui/
│   ├── MainActivity.kt           Dashboard (D-pad menu)
│   ├── SetupActivity.kt          Six-step permission wizard
│   ├── DevicePickerActivity.kt   Host phone selector
│   ├── DialerActivity.kt         Keypad dialer (host or local SIM)
│   ├── IncomingCallActivity.kt   Full-screen ring UI
│   └── InCallActivity.kt         Active-call UI (mute/speaker/DTMF)
└── util/
    ├── FlipSensor.kt             Hall-sensor / lid-state flip detection
    └── Prefs.kt                  Settings storage
```

## Known limitations

Call audio in AT-command mode remains on the host phone; this is an Android platform restriction, not an app bug (see `SETUP_GUIDE.md` § Audio modes). Multi-call handling (call waiting swap, 3-way merge) is not yet implemented, and the Fresno's non-touch screen means all UI is keypad-driven by design.

## License

MIT — see [LICENSE](LICENSE).

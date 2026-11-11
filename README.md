# NOVA Dialer

The dialer for **NOVA OS** — a modern, fast phone app for Android 10+.

- **Keypad** with DTMF tones, T9 search, long-press 0 for `+`, long-press 1 for voicemail
- **Recents** — All / Missed filter, SIM label, long-press menu: call back, copy number, add to contacts, block/unblock, delete from log, details
- **Contacts** — favorites row, search, star, edit via the system editor
- **In-call screen** — answer/decline, mute, speaker, hold, DTMF keypad, add call, call timer (keeps counting through hold), via `InCallService`
- **Block numbers** (needs the default-dialer role)
- **Set as default** — one-tap default-dialer role request
- **Dark / light theme**, persisted
- **In-app Error log** — every crash and failed operation is recorded with its time, so problems can be reported with the exact error

## Build

This project does **not** use Gradle. It builds with a manual pipeline
(`aapt2` + `kotlinc` + `d8` + `apksigner`), which is what `tools/build-apk.sh` runs.

### Prerequisites

- Android SDK with `platforms/android-35` and `build-tools/35.0.0`
  (set `ANDROID_HOME`, e.g. `~/android-sdk`)
- JDK 17 (set `JAVA_HOME`)
- The NOVA Files toolchain — this build reuses the JDK, kotlinc compiler and
  dependency jars from a NOVA-Files checkout. Check it out next to this repo
  (or set `NOVA_FILES_ROOT` to its path). The Kotlin compiler 2.0.21 jars
  (`kotlin-compiler-embeddable-2.0.21.jar`, the Compose compiler plugin)
  and `trove4j` come from that checkout (see `tools/build-apk.sh`).

### Steps

```bash
# 1. Fetch the dependencies into deps/ (not committed — AARs/JARs)
python3 tools/resolve_deps.py deps/ $(cat tools/deps.txt)

# 2. Extract them into the build tree
python3 tools/prep_deps.py

# 3. Build the APK
bash tools/build-apk.sh
# -> build/apk/nova-dialer-debug.apk
```

The APK is signed with a debug keystore generated on first build.
Minimum SDK 29 (Android 10), target SDK 35.

## Version history

- **v1.4** — Full audit fixes: Recents no longer uses `LIMIT` in the
  sort order (the same provider rejection that emptied the Gallery);
  dialing keeps `*` and `#` so USSD codes like `*#06#` work
  (`Uri.fromParts`); the in-call DTMF tone now plays ~150ms instead of
  being cut off in the same frame; turning the speaker off restores the
  previous audio route instead of forcing the earpiece; "Add call" now
  shows the Hold state.
- **v1.3** — The system back button now steps back — from Recents or
  Contacts to the keypad, and the error log closes — instead of exiting
  the app.
- **v1.2** — Permission-crash fix: the Activity Result API always generates a
  permission request code of 65536 or higher, and `FragmentActivity` rejects
  any code above the lower 16 bits — so tapping "Allow access" always
  crashed. `MainActivity` now extends `ComponentActivity`.
- **v1.1** — Launch-crash fix: the manifest pointed the launcher at
  `com.nova.dialer.MainActivity`, but the class lives in
  `com.nova.dialer.ui.MainActivity` — the app crashed the moment it was
  opened. Fixed to `.ui.MainActivity`.
- **v1.0** — Keypad + T9, Recents, Contacts, in-call screen, block numbers,
  set-as-default flow, themes, Error log.

## License

MIT — see [LICENSE](LICENSE).

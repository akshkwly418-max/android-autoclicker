# Auto Clicker (Android, Kotlin)

A minimal, open-source **auto clicker** for Android, written in Kotlin and
built with the modern Gradle Kotlin DSL. The click is dispatched via an
`AccessibilityService` (no root required) and the app surfaces a **floating
control button** drawn on top of every other app so you can start/stop the
click loop without leaving whatever you are doing.

The repository ships with a GitHub Actions workflow that builds the APK on
every push to `main` (or tag `v*`) and attaches the resulting `app-release.apk`
to a GitHub Release tagged `latest`, so anyone with the link can download
and install it.

---

## Features

- **No root required.** Taps are dispatched using
  [`AccessibilityService.dispatchGesture`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)),
  the same gesture API the OS uses to forward user touches to apps.
- **Configurable**:
  - **Interval** between taps (100 ms – 5000 ms).
  - **Tap duration** (1 ms – 500 ms), which lets you simulate long-presses.
  - **Repeat count** (0 = infinite, otherwise N taps).
  - **Tap position** — either the centre of the screen (sensible default that
    works for any app) or custom X/Y coordinates that you type in.
- **Floating control button** that you can drag anywhere on the screen and
  tap to start/stop the clicker.
- **Foreground service** with a sticky notification so the OS does not kill
  the floating button while you are in another app.
- **Material 3** light/dark theme, day/night support out of the box.
- **Signed release APK** that installs on any developer device out of the box
  (the release build is signed with the Android debug key — see the workflow
  file for how to override this with your own keystore).

---

## How it works

```
┌──────────────────┐   permissions   ┌─────────────────────────┐
│   MainActivity   │ ───────────────►│ System Settings          │
│  (UI + sliders)  │                 │ • Overlay permission     │
└────────┬─────────┘                 │ • Accessibility service  │
         │ TapConfig(x, y, interval, │                         │
         │           duration,       └─────────────────────────┘
         │           repeat)                    ▲
         ▼                                     │ bound?
┌──────────────────────────────┐               │
│ AutoClickService.Controller │ ──────────────┘
│  (AccessibilityService)      │
│  dispatchGesture(StrokePath) │
└──────────────────────────────┘
        ▲ starts / stops
        │
┌──────────────────────────────┐
│ FloatingControlsService      │
│ (Foreground service + overlay│
│  window with a draggable     │
│  Start/Stop button)          │
└──────────────────────────────┘
```

The MainActivity collects the user's tap configuration, the floating control
service draws the start/stop button on top of other apps, and the bound
`AutoClickService` repeatedly calls `dispatchGesture` to fire taps at the
requested position with the requested interval.

---

## Build it locally

Requirements:

- **JDK 17**
- **Android SDK 34** (set `ANDROID_HOME` env var, or have a `local.properties`
  file with `sdk.dir=...`)

```bash
# Make the wrapper executable (only needed once after cloning on Unix)
chmod +x ./gradlew

# Build both debug and release APKs
./gradlew assembleDebug assembleRelease

# Find the APKs here:
#   app/build/outputs/apk/debug/app-debug.apk
#   app/build/outputs/apk/release/app-release.apk
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## Run it

1. Launch **Auto Clicker**.
2. Tap **"Grant overlay permission"** and toggle the switch on for the app.
3. Tap **"Open Accessibility Settings"** and enable **Auto Clicker Service**
   from the list of installed accessibility services.
4. (Optional) Adjust interval / duration / repeat / position.
5. Tap **Start**. You will be bounced to the home screen and a circular
   floating button will appear in the top-right.
6. Tap the floating button to **stop** the click loop. Drag it anywhere you
   want to keep it out of the way.

> ⚠️ The app uses the standard Android accessibility-service gesture API.
> Some games actively detect and block accessibility-driven taps — that is
> not something this app can work around.

---

## CI / Release

The workflow at [`.github/workflows/build-and-release.yml`](.github/workflows/build-and-release.yml)
runs on:

- every push to `main` or `master`,
- every tag matching `v*` (e.g. `v1.0.0`),
- manual dispatch from the **Actions** tab.

It:

1. Checks out the repo,
2. Installs JDK 17 + Android SDK 34,
3. Runs `./gradlew assembleDebug assembleRelease`,
4. Uploads the resulting APKs as **workflow run artifacts** (kept for 30 days),
5. Creates/updates a GitHub Release tagged **`latest`** and attaches the
   debug and release APKs.

After the first successful run, the installable APK will be available at:

```
https://github.com/<your-username>/<your-repo>/releases/latest
```

### Overriding the signing key

By default the release APK is signed with the Android debug key so it
installs on developer devices without any extra setup. To sign with your own
keystore, replace this block in [`app/build.gradle.kts`](app/build.gradle.kts):

```kotlin
release {
    ...
    signingConfig = signingConfigs.getByName("debug")
}
```

with a `signingConfigs.create("release") { ... }` block that reads
`storeFile`, `storePassword`, `keyAlias`, `keyPassword` from environment
variables or a `keystore.properties` file.

---

## Project layout

```
.
├── .github/workflows/build-and-release.yml   # CI: build APK + create release
├── app/
│   ├── build.gradle.kts                      # Module-level Gradle config
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/zai/autoclicker/
│       │   ├── MainActivity.kt                # UI + permission orchestration
│       │   ├── AutoClickService.kt            # AccessibilityService + tap loop
│       │   └── FloatingControlsService.kt     # Draggable overlay + foreground svc
│       └── res/
│           ├── drawable/  layout/  values/  xml/
│           └── mipmap-anydpi-v26/             # Adaptive launcher icon
├── build.gradle.kts                          # Project-level Gradle config
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/                            # Gradle 8.7
├── gradlew / gradlew.bat                       # Wrapper scripts
├── .gitignore
└── README.md
```

---

## Disclaimer

This project is intended for personal automation, accessibility testing, and
development. Using it to cheat in games or to abuse other apps may violate
the relevant terms of service. Use responsibly and at your own risk.

## License

MIT — see the [LICENSE](LICENSE) file for details.

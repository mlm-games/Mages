# Mages

Mages is an experimental matrix chat client.

- UI: [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
- Core: a Rust library built on top of [matrix-rust-sdk](https://github.com/matrix-org/matrix-rust-sdk), exposed to Kotlin via UniFFI (not using matrix-sdk-ffi)

The goal was to have a cross‑platform desktop/mobile client, while not being as heavy as web apps (initially there were very few alternatives to element-desktop which notify, with proper screen-sharing support and that stay hidden in my tray (unlike neochat, though it has linux-specific push notifs via kunifiedpush, while this currently polls)). 

The client might work well for most, but is never aiming to be as stable as element's clients (or other clients like fluffy or fractal which seem to have a user-focused goal)

## Status 

This is experimental‑stage software. It is still in its early stages, and might lack common features or have (game/use-breaking?) bugs (do open an issue if you've encountered any)

## Features (as of the last README update)

- Room list with previews and unread counts
- Room and thread timelines (text, media, polls, live-location sharing)
- End‑to‑end encryption (via matrix‑sdk)
- Spaces support (no sub-spaces support yet)
- Simple presence / privacy settings
- Android app and Linux desktop builds (Available on/as FDroid, AppImage (updatable via `Gear Lever` or similar tools), AUR (`mages-bin`), snapcraft and flathub)
- Unsigned Windows and Mac builds
- Experimental Audio and Video Calls Support (Screen sharing is only available on desktop)
- Multi account and language (currently only spanish) support 

## Platforms

- **Android**  
  - Signed APKs and AABs are published on GitHub Releases, or are downloadable via F-Droid (does sometimes lag behind).

- **Linux desktop**  
  - AppImage builds for x86_64 and aarch64.
  - Downloadable via AUR and snap-store.

- **Other platforms**  
  - The UI is Compose Multiplatform. In practice, only Android and Linux's AppImages are actively tested. Windows and Mac are tested rarely and are not signed/notarized. [Web](https://mlm-games.github.io/Mages) works, but isn't supposed to have feature parity (no search functionality), and mostly useful for testing newer unreleased changes or previewing the app.

## Architecture

- **Rust core**  
  - Uses `matrix-sdk` and `matrix-sdk-ui` for sync, room list, timelines, E2EE, etc.
  - Exposed as a UniFFI library (`mages_ffi`) that Kotlin/JVM can call.

- **Kotlin UI**  
  - Compose Multiplatform for Android and desktop.
  - Koin for dependency injection.
  - ViewModels for state, backed by the Rust core.

Most Matrix‑specific logic lives in Rust; Kotlin mostly handles presentation.

## Building from source

### Prerequisites

- JDK 21
- Kotlin/Gradle
- Rust toolchain (stable) and compile targets (depending on target arch, running would specify them automatically)
- For Android:
  - Android SDK + NDK (see `android-release` workflow for versions)
  - `cargo-ndk` (for building the Rust library for Android ABIs)

### Android

```bash
./gradlew :androidApp:assembleRelease
# APKs end up under androidApp/build/outputs/apk/release
```

### Desktop (Linux)

```bash
./gradlew :desktopApp:run
```

### Web

```bash
./gradlew :webApp:wasmJsBrowserDevelopmentRun
```

## Version Trackers

| Platform   | Version                                                                                                                              |
|------------|--------------------------------------------------------------------------------------------------------------------------------------|
| F-Droid    | [![F-Droid Version](https://img.shields.io/f-droid/v/org.mlm.mages)](https://f-droid.org/packages/org.mlm.mages/)                    |
| AUR        | [![AUR Version](https://img.shields.io/aur/version/mages-bin)](https://aur.archlinux.org/packages/mages-bin)                         |
| Flathub    | [![Flathub Version](https://img.shields.io/flathub/v/io.github.mlm_games.mages)](https://flathub.org/apps/io.github.mlm_games.mages) |
| Snap Store | [![Snapcraft Version](https://img.shields.io/snapcraft/v/mages/latest/stable)](https://snapcraft.io/mages)                           |

## Contributing

Issues and small PRs are welcome. Please keep changes focused and self‑contained.

## License

Mages is licensed under the **GNU AGPL v3** (see `LICENSE`), with all the artwork, logos, and visual assets being licensed under a [CC BY-SA 4.0 License](https://creativecommons.org/licenses/by-sa/4.0/).

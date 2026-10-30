# Native32Emu Android app

This directory contains the standalone Android wrapper for Native32Emu. It is separate from RetroArch and packages the EmulatorJS runtime plus the project's own Native32Emu WebAssembly core into an Android APK.

## Build from GitHub Actions

1. Open the repository's Actions tab.
2. Choose Build Native32Emu Android APK.
3. Press Run workflow and select the branch you want to build.
4. When the run finishes successfully, open the run and download the Native32Emu-Android-APK artifact.
5. Extract the ZIP and install app-debug.apk on the phone.

The APK is currently built as a debug-signed test build so Android can install it directly. It is not a Play Store release.

## App behavior

- Imports .zip, .smf, .sgm, and .ssl files through Android's system file picker.
- Keeps imported game files in the app's private storage and lists them in the library.
- Lets you add a cover image to each game from an image already on the phone.
- Runs the bundled EmulatorJS runtime and Native32Emu core offline after the APK is built.
- Shows the emulator's touch controls when the game is running.
- The emulator core supports Native32 .ssl_sav game saves; persistence depends on the game's own save behavior and the EmulatorJS persistent save directory.

Game data is not bundled. Use game files you are legally entitled to use. Some game archives need their original folder structure and supporting assets.

## Technical note

The core is compiled from native32emu-libretro for WebAssembly/Emscripten, then packaged with EmulatorJS. This reuses the existing emulator engine rather than pretending that the Android libretro .so is itself an APK.

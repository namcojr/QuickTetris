# QuickTetris

An Android app that boots straight into **Atari Tetris (1988 arcade)**, running on MAME, with a touch control panel styled like an arcade cabinet and CRT shaders you can switch while playing.

It has no frontend, menus or ROM browser. Open the app and insert a coin.

## Features

- **Instant boot:** MAME 0.289 built for one driver (`atetris`) and launched with no info screens.
- **Arcade control panel:** multi-touch buttons drawn as a screwed-on metal plate. You can slide a finger between LEFT and RIGHT without lifting it, and short taps are held long enough that MAME always registers them.
- **Live CRT shaders:** tap the game to cycle through bgfx chains without restarting:
  | Shader | Look |
  |---|---|
  | CRT-GEOM | Curved tube, scanlines, shadow mask |
  | CRT-GEOM DELUXE | Halation, bloom, phosphor glow |
  | HLSL CRT | NTSC artifacts, convergence, bloom, scanlines |
  | No shader | Clean filtered pixels |
- **Aspect toggle:** long-press the game to switch between a mild vertical stretch that fills the screen and the true 4:3 image with black bars. The control panel stays the same size in both modes.
- The app remembers your shader and aspect choice between launches.

## Controls

```
        COIN            START
   LEFT      RIGHT   DROP    ROTATE
```

| Gesture on the game | Action |
|---|---|
| Tap | Next CRT shader |
| Long press | Toggle stretched / true 4:3 |
| Back button | Quit |

Atari Tetris has no dedicated start input: the cabinet wires START and ROTATE to the same button, and the app does the same.

## Requirements

- Android 8.0+ (API 26), **arm64-v8a** only
- An OpenGL ES device (bgfx runs with the `gles` backend, and only ESSL shaders are shipped)
- Heavier shaders (CRT-GEOM DELUXE, HLSL) need a reasonably modern GPU.

## Building

The repository does **not** include the ROM or the native MAME library. You must provide both.

### 1. ROM

Put your own legally obtained `atetris.zip` (MAME 0.289 romset) at:

```
roms/atetris.zip
```

Gradle packages it into the APK's assets at build time. The build fails if the file is missing.

### 2. Native library (`libmain.so`)

`libmain.so` is MAME built for Android with `native/mame-bgfx-chain-hook.patch` applied. The patch adds:

- a JNI entry point, `MameActivity.nativeSetScreenChain`, so bgfx chains can switch at runtime;
- ESSL shader path resolution on Android.

Build steps:

1. Clone MAME and check out `mame0289` (the patch was made against `d1bdede14cb`), then apply the patch:
   ```sh
   git clone https://github.com/mamedev/mame.git ~/mame
   cd ~/mame && git checkout d1bdede14cb
   git apply /path/to/QuickTetris/native/mame-bgfx-chain-hook.patch
   ```
2. Build or install **SDL 3.4.x** for `arm64-v8a`. It must match the SDL Java sources in `app/src/main/java/org/libsdl/app`.
3. Run the build script. It needs NDK 27.3.13750724 (set in the script):
   ```sh
   SDL_INSTALL_ROOT=/path/to/sdl3-arm64-prefix native/build-libmain.sh
   ```
   The script builds only the `atetris` driver, relinks with `-z max-page-size=16384`, strips the result and copies it to `app/src/main/jniLibs/arm64-v8a/libmain.so`.

   > The 16 KB relink is required. A plain `make` output crashes at startup on devices with 16 KB memory pages.

Also copy `libSDL3.so` and `libc++_shared.so` (from the NDK) into `app/src/main/jniLibs/arm64-v8a/`.

### 3. APK

```sh
./gradlew assembleDebug      # or assembleRelease
```

To sign release builds, create `keystore.properties` in the repository root:

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without this file, the release APK is built unsigned.

## How it works

- `MameActivity` extends SDL's `SDLActivity`. On first launch, and after every update, it copies the ROM and the bgfx shader tree from the APK assets into internal storage, then starts MAME with absolute `-rompath`, `-bgfx_path` and `-artpath` arguments.
- `ControlPadView` is docked below the game. It sends Android key codes to SDL, which match MAME's default keyboard bindings (`5` = coin, arrow keys, `Left Ctrl` = button 1). Keys are reference-counted, so START and ROTATE don't release each other.
- MAME runs with `-nokeepaspect` and always fills its surface. The aspect toggle resizes the SDL surface itself: it either fills the area above the panel or becomes a centered 4:3 box.
- `ShaderToastView` is the plaque that shows the active shader or aspect mode. It never takes touches.

## Project layout

```
app/src/main/kotlin/com/namco/quicktetris/   App code (activity, control pad, toast, art)
app/src/main/java/org/libsdl/app/            SDL 3 Android Java glue
app/src/main/assets/mame/                    bgfx chains, effects, ESSL shaders, shadow-mask artwork
native/                                      MAME patch and libmain.so build script
roms/                                        Your atetris.zip (gitignored)
```

## Legal

- Tetris® is a trademark of The Tetris Company. This project is not affiliated with The Tetris Company, Atari Games or Tengen.
- **No ROMs are included.** Only use ROM images you are legally entitled to.
- MAME is distributed under the GPL-2.0-or-later (see [mamedev/mame](https://github.com/mamedev/mame)). The bgfx shaders and chains shipped in `assets/mame/bgfx` keep their original licenses (see the `LICENSE` files in that folder).
- SDL is distributed under the zlib license.

# SRT Studio

Turn a subtitle file (`.srt`), a set of images and an optional audio track into a finished MP4, entirely on your phone.
Native Android app: Kotlin, Jetpack Compose (Material 3), MediaCodec + OpenGL ES for encoding.

## Features

- **Live preview** that uses the exact same renderer as the final video (what you see is what you get)
- Captions in **Outline / Box / Shadow** styles, 3 positions, 5 colours, adjustable size, **custom font** (great for Arabic calligraphy fonts)
- **Right-to-left text** (Arabic, Hebrew, Persian, Urdu) laid out and shaped correctly
- Images: natural sort (`img2` before `img10`), reverse / shuffle, EXIF rotation fixed, **Ken Burns** zoom/pan, **crossfades**
- Images are mapped **one per caption** or **spread evenly** across the timeline
- Audio: any format the phone can decode (MP3, WAV, M4A, OGG, FLAC...). Converted to AAC automatically
- Output: 720p / 1080p, landscape / portrait / square, 24 / 30 / 60 fps, saved to `Movies/SRT Studio`
- Renders in a **foreground service** with a progress notification and a Cancel button, so it survives leaving the app
- Reads legacy-encoded subtitle files (Windows-1256 Arabic, Windows-1252) as well as UTF-8/16
- **No network permission, no analytics, no ads, no push**. Nothing leaves the device

## Build it with GitHub

See **[BUILD_GUIDE.md](BUILD_GUIDE.md)**. Short version: push this folder to a GitHub repo and open the **Actions** tab. A signed, installable APK appears as a download after about 5 minutes.

## Project layout

```
app/src/main/java/com/srtstudio/app/
  MainActivity.kt           entry point
  data/                     SrtParser, models, preferences, file helpers
  render/
    FrameRenderer.kt        draws one frame (photo, motion, caption) onto a Bitmap
    EncoderGlSurface.kt     feeds frames to the encoder with exact timestamps
    VideoRenderer.kt        encoder + muxer + audio interleaving
    AudioTranscoder.kt      any audio -> AAC
  service/                  RenderController (state) + RenderService (foreground)
  ui/                       Compose screen, components, theme, ViewModel
```

## Run the unit tests

```
./gradlew testDebugUnitTest
```

# Gyro-less Motion Photo stabilization

The Photo mode setting **Stabilize Motion Photo (experimental)** is stored per app install and defaults to OFF. Enable Live Photo in the camera first. Ordinary video stabilization is separate.

On capture, `LivePhotoRecorder.recordVideo` produces a short temporary MP4 and shutter-relative timestamp. `CameraViewModel` passes the capture's preference snapshot to `GalleryManager.saveVideo`. When enabled, `MotionPhotoStabilizer` decodes luma frames, tracks a grid of textured background patches with robust median outlier rejection, smooths a bounded translation trajectory, crops 10% on each edge and warps decoded frames into an H.264 encoder surface through GLES. It copies source audio samples into the new MP4 with their timestamps rebased to the first source video frame. The resulting MP4 is checked for video dimensions, rotation, duration and audio timeline before publication. `MotionPhotoWriter` and the still JPEG are unchanged.

The existing gallery thumbnail processing overlay remains visible while the capture queue saves the photo and video. The original MP4 remains available until a valid output and timestamp are saved. Low texture, excessive displacement, unsupported codec/resolution, timeout, codec errors and invalid output cause fallback to the original. The saved video's temporary copy is published within its directory before export. This pipeline uses no sensor motion or network service.

## Build and checks

```sh
./gradlew :app:testDefaultDebugUnitTest --tests 'com.hinnka.mycamera.livephoto.*'
./gradlew :app:assembleDefaultDebug
find app/build/outputs/apk -name '*.apk'
```

On a machine with about 4 GiB RAM, pass these options to either Gradle command:

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:assembleDefaultDebug \
  -Dorg.gradle.jvmargs='-Xmx1280m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  -Pkotlin.daemon.jvmargs='-Xmx2048m -XX:MaxMetaspaceSize=512m' \
  --max-workers=1
```

Validation on 2026-10-19: `assembleDefaultDebug` succeeded and produced
`app/build/outputs/apk/default/debug/app-default-debug.apk`. Ten Motion Photo
unit tests passed. The checkout's existing `BokehShadersTest.kt` does not
compile because it calls three shader helpers absent from `Shaders.kt`; that
file was temporarily removed from the unit-test source set for the focused
run and restored afterward. The existing instrumented
`OglBokehProcessorShaderTest.kt` similarly uses an obsolete Bokeh API; with
that file temporarily excluded, `compileDefaultDebugAndroidTestKotlin`
succeeded. No connected device was available, so instrumented tests and
Xiaomi Gallery playback remain unverified. Android Lint was attempted but
stopped during whole-project analysis after more than 15 minutes on this
RAM-limited machine; there is no completed lint result.

The debug build already has the `.debug` application ID suffix. Install only the debug APK you built; do not replace a production app. The instrumented fallback test requires a connected Android device or emulator:

```sh
./gradlew :app:connectedDefaultDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hinnka.mycamera.livephoto.MotionPhotoStabilizerTest
```

## Redmi 13C test

1. Enable Live Photo. Capture a steady daylight scene with stabilization OFF, then ON, using similar small hand motion. Compare background displacement during Gallery playback.
2. Confirm the still frame looks the same, the clip animates in Xiaomi Gallery and another compatible viewer, the shutter moment is aligned, and audio is present and synchronized.
3. Repeat in portrait and landscape, low light and with moving subjects. Inspect borders, wobble, blur, zoom and rotation.
4. Capture a scene with low texture or rapid shake to exercise fallback. Confirm a Motion Photo is still saved.
5. Record processing time, memory/thermal behavior and any codec or gallery errors from device logs. A source build or emulator test does not establish Xiaomi Gallery compatibility.

The crop cannot recover blur already present within a frame. Translation estimation cannot correct rotation or rolling shutter, and low light or a dominant moving subject may trigger fallback. Captures over 180 frames, above 1920x1080 pixel count, or over 30 seconds of processing fall back. Device tuning is pending.

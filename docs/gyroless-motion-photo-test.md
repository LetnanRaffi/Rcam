# Gyro-less Motion Photo stabilization

The Photo mode setting **Stabilize Motion Photo (experimental)** is stored per app install and defaults to ON for new installs. Existing saved ON/OFF choices are preserved. Enable Live Photo in the camera first. Ordinary video stabilization is separate.

On capture, `LivePhotoRecorder.recordVideo` produces a short temporary MP4 and shutter-relative timestamp. `CameraViewModel` snapshots the preference at shutter time. The still photo is saved and made viewable before video completion. A bounded background job then waits for post-roll, processes the clip, publishes the final MP4 and only then auto-exports the Motion Photo. Manual export while the clip is processing is rejected. Internal playback waits for the final nonempty file and enters near the primary photo timestamp.

When enabled, `MotionPhotoStabilizer` decodes luma frames and tracks spatially distributed corners. Deterministic two-point RANSAC estimates a dominant background similarity transform, selecting translation when rotation/scale do not improve fit. A bounded Huber acceleration optimizer smooths the complete path, preserves steady pans, splits at detected scene cuts and anchors the shutter frame. An isolated low-confidence pair keeps the path continuous. One fixed crop is checked against all warped corners. GLES applies translation, rotation and scale into an H.264 encoder surface using the decoded frame PTS; source audio is remuxed with the corresponding timestamp offset. The output MP4 is checked for video dimensions, rotation, duration and audio timeline before atomic publication. Original video remains available for fallback. `MotionPhotoWriter` and the still JPEG are unchanged.

The processing state distinguishes global stabilization, a reduced model with unreliable frame pairs, already-stable input and original fallback in logs/status. Low texture, excessive displacement, unsupported codec/resolution, timeout, codec errors and invalid output can cause fallback to the original. Stabilization jobs run one at a time, with at most two queued; further captures retain their original clip. This pipeline uses no sensor motion or network service. No spatial mesh or rolling-shutter correction is implemented; the conservative global model avoids unsupported local deformation.

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

PRD v3 validation on the same date: `compileDefaultDebugKotlin` passed after
the global similarity/Huber path and nonblocking Motion Photo integration.
Sixteen focused Live/Motion Photo JVM tests passed, including a ground-truth
synthetic pan with alternating shake: mean absolute step residual decreased
from 5.0 to 0.56557024 analysis pixels (88.7%). This tests motion math, not a
decoded/encoded MP4. The unchanged `BokehShadersTest.kt` still fails to compile
against three missing shader helpers, so it was temporarily excluded only
during the focused run and restored afterward. No connected Android device
or matching arm64 emulator is available for the MediaCodec/GLES instrumented
test. Codec output, Xiaomi Gallery playback, and Redmi 13C visual quality are
not yet validated on a device.

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

The crop cannot recover blur already present within a frame. The global similarity model corrects translation and rotation but not parallax or rolling shutter; low light or a dominant moving subject may trigger fallback. Captures over 180 frames, above 1920x1080 pixel count, or over 60 seconds of processing fall back. Device tuning is pending.

# RCAM 2.0 — iOS 26 Camera Redesign + Native Timestamp Camera

> **Execution brief for coding agent.** Implement in the existing Rcam Android repository, not in HTML. The latest Rcam visual mockup and reference screenshots of the Timestamp Camera app used by the owner must be attached to the agent. Preserve Rcam's camera features and photo/video processing. Deliver a working APK and pushed source changes.

## 0. Outcome and priorities

Create an app that feels newly built, not like PhotonCamera with a theme. **Visually and behaviorally emulate the iPhone Camera app in iOS 26 as closely as feasible**, using original Rcam components and assets, while preserving Rcam's existing features. Add a **real, native Timestamp mode** for trustworthy opening/closing store documentation. Its **saved-photo appearance should closely match the familiar Android Timestamp Camera watermark** so a manager immediately recognizes the same information and layout. This is about consistent documentation, **not impersonating another application's provenance**. Do not add fake app signatures, verification badges, logos, or metadata claiming the image was captured by a different app.

**Priorities:** (1) correct native capture and trustworthy timestamp/GPS data; (2) visual fidelity and interaction design; (3) preserve current camera/LUT/video/Motion Photo; (4) acceptable Redmi 13C performance; (5) build APK, commit, push.

## 1. Inspect first, then implement

- Inspect actual repository structure, UI framework (XML/Views/Compose), preview implementation (`SurfaceView`, `TextureView`, GL, etc.), camera modes, Camera2 capture callbacks, image processing, JPEG writing, EXIF, MediaStore, gallery, LUT/presets/frames, video and Motion Photo.
- Identify **where the final full-resolution photo exists after all existing LUT/image processing but before the JPEG is published**. Timestamp compositing belongs there, not in a screenshot of the UI and not in the camera preview layer alone.
- Check Android SDK/target SDK, existing location dependencies, device capability handling, orientation/mirroring and background threading.
- Study **real iOS 26 Camera UI** and the supplied mockup. Study **real Timestamp Camera Android output** (especially the owner's reference screenshot) and match its field order, typography, placement, panel proportions, date/time format and location presentation. If no actual Timestamp Camera reference screenshot is attached, use a recognizable, configurable documentary timestamp template, and clearly report the visual matching limitation. Do not claim pixel-perfect matching without a reference.
- Study BitChord/Haze implementation for **techniques** behind translucent glass, blur, highlights and morphing, not for copying unrelated app code. Respect dependency and source licensing if reused.

## 2. Complete iOS 26-inspired camera presentation overhaul

### Default camera screen

- **Discard the old PhotonCamera UI composition**: no five black buttons in a heavy top bar, no persistent black footer with square EV/Tv/ISO/AF/AWB buttons, no Material-style card pile.
- Make the live camera image the dominant content. Honor sensor aspect ratio and composition; don't crop the captured framing just to fill the screen. Use subdued dark surround only where framing requires it.
- Minimal top area: compact flash control, small expandable Quick Controls affordance, contextual status; avoid five permanently exposed actions.
- Near lower preview: compact zoom selector showing **only supported zoom factors**; a **permanently accessible ✨ Styles button** on the right. ✨ must be a one-tap entry to **LUT / Bingkai / Presets**, not buried in Quick Controls.
- Capture row: gallery thumbnail left, prominent white shutter center, camera flip right. Video shutter and recording state are visually distinct.
- Bottom mode strip: visually prioritize **Foto and Video** like iOS 26; **Pro, Motion, Timestamp** remain reachable by horizontal mode navigation. Keep selected mode centered and legible; do not permanently crowd five labels.

### Layout precision

- Responsive safe-area/insets handling for status bar, camera cutout, gesture navigation and varying device sizes.
- Consistent 8/12/16/24 dp spacing rhythm, at least ~48 dp interactive targets, aligned optical centers, balanced shutter/thumbnail/switch geometry.
- Glass controls should feel physically translucent: selective backdrop-aware blur where feasible, adaptive tint, edge highlights, gentle inner depth and refraction where affordable. **Do not equate Liquid Glass with a dark alpha background and border.**
- Inspect preview surface restrictions: if `SurfaceView` cannot be sampled for backdrop blur, use an honest performant fallback, rather than black opaque panels, continuous screenshots or expensive GPU readbacks.
- Keep decorative effects out of shutter critical path. Target responsive animation on Helio G85, but do not assert measured FPS without real device measurement.

### Motion and gestures

- One **shared moving glass selection indicator** for modes: it translates, adapts width, subtly stretches, and settles with restrained spring physics. Do not merely swap tab background colors.
- Smooth Photo ↔ Video shutter transformation, expandable controls, drawer expansion, contextual Pro adjustments, settings navigation and gallery image transitions.
- Gestures must not conflict with pinch zoom, focus or existing camera controls. Camera session readiness must reflect real hardware state, not animation completion.

### Hidden but accessible controls

- Top Quick Controls expands/collapses as a glass panel; put Flash, Timer, Grid, Histogram, Aspect, level, applicable Motion options, and settings shortcuts here according to actual capabilities.
- Pro mode retains EV/Tv/ISO/AF/AWB but shows one compact manual strip; expand **only the selected parameter** into a slider/selector, then collapse. No feature removal.
- ✨ opens one morphing glass drawer with tabs **LUT | Bingkai | Presets**, existing real entries/categories, thumbnails, selection/favorites and existing applicable intensity controls. Preserve the underlying LUT algorithm, especially **normal Video LUT processing**.
- Gallery and Settings must be redesigned using the same minimal glass design language, with all existing actions and options retained. Avoid oversized translucent cards for every setting.

## 3. Timestamp is a **real native camera mode**

Timestamp appears in the camera mode strip and uses **Rcam's existing Camera2 sensor/capture pipeline and selected compatible photo LUT**. It is not an HTML screen, WebView, decorative TextView-only overlay, or a separate camera implementation.

### Entry and permission flow

1. User swipes to **Timestamp**. Show a live documentary timestamp overlay, with placeholder states only for fields not yet available.
2. If location is enabled in Timestamp settings and permission is missing, show an understandable, contextual permission explanation, then request `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` using the appropriate Android runtime permission API. Respect approximate-only permission. Do not ask for background location for a foreground camera use case.
3. If denied, continue capturing date/time photos; show **Lokasi tidak tersedia** or hide location per user settings. Never fabricate an address or coordinates.
4. On permission grant, request an appropriately fresh location with `FusedLocationProviderClient.getCurrentLocation` when available, or Android `LocationManager` as compatible fallback. Handle GPS disabled, timeout, cancellation, no fix, stale cache and app lifecycle. Don't block shutter indefinitely waiting for GPS.
5. Resolve address asynchronously with Android `Geocoder` (asynchronous API where supported), with clear timeout/failure handling. Network-based reverse geocoding may be unavailable; coordinates remain useful without a street address. Never invent a street name.
6. Show **location accuracy**, loading/failure state and a retry action. Give the user a clear choice to capture without location if a fix is unavailable.
7. Save privacy preference; provide an easy way to disable location watermarking. Do not start background tracking.

### Time and data integrity

- Capture the **actual device clock timestamp at shutter/capture event**, preferably tied to a stable capture event and paired with its metadata; freeze it per photo. Do not render an updating clock after the shot and accidentally use the later save time.
- Use the configured timezone and clear date/time format; default to Indonesian conventions and local timezone. Correctly handle midnight/date changes.
- Bind the location snapshot to the shot, recording latitude, longitude, accuracy, source/fix time and freshness. Never silently substitute an old cached position. If a fix is stale or inaccurate, show that limitation or omit location based on settings.
- Printed watermark and EXIF must agree on the available facts. Preserve existing camera EXIF (orientation, exposure, camera metadata as applicable), and add correct GPS EXIF when location is permitted and valid.
- **Do not present a printed timestamp as tamper-proof evidence.** A watermark can be edited; it is a documentation aid. Optionally provide a separate immutable capture record/manifest with photo hash and metadata if useful, but never claim cryptographic authenticity without actually implementing verification.

### Familiar Timestamp Camera-style output

- Build a configurable **documentary watermark template** closely matching the owner's reference Timestamp Camera Android app output: date, time, location/address, coordinates, optional custom text, optional accuracy. Match its field order, typographic emphasis, padding, background opacity, icon usage (if applicable), line spacing, and corner placement.
- Default to a **readable, professional documentary layout** recognizable to store managers. Avoid the tiny elegant iOS overlay if it makes the printed evidence unreadable. The camera UI can be iOS-like while the **saved watermark follows Timestamp Camera's familiar documentation style**.
- Include timestamp style/size/position controls and a preview; allow hide/show of individual fields. Keep defaults practical and reproducible for opening and closing store reports.
- Do **not** forge another app's logo, watermark signature, verified status, EXIF `Software` value, or proprietary brand identity to suggest the image was made by that app. Rcam should produce equivalent visible documentation, not falsely claim another source.
- Include an optional **Opening / Closing** custom label preset if this fits existing settings, but it must be clearly user-supplied, not auto-asserted as verified business activity.

### What must be in the saved JPEG

- A timestamp drawn over preview **is insufficient**. Render the watermark into the **final full-resolution bitmap/image buffer**, after compatible photo LUT/processing and before final JPEG write and MediaStore publish. Preserve high image quality.
- Reconcile preview crop vs saved image crop, EXIF orientation, device rotation, front-camera mirror state, aspect ratio and edge insets. The watermark must land in the intended position on the **actual saved photo**, not at a shifted or clipped position.
- Avoid repeatedly decoding full-size bitmaps on the main thread. Use bounded-memory processing and proper resource cleanup.
- Ensure the saved image viewed in **Rcam Gallery and external Android Gallery** visibly contains the watermark. No dependency on an overlay rendered by Rcam's gallery viewer.
- Publish only after the final image is fully written; use MediaStore pending semantics where applicable and handle failures without corrupt gallery entries.
- Keep a clear distinction between capture time, save time and location fix time.

## 4. Timestamp 4-Grid toggle

- **4-Grid is a toggle inside Timestamp, not its own navigation mode.** Show a clear glass toggle directly in Timestamp UI.
- Off: one normal watermarked photo per shutter press.
- On: four **sequential** shutter presses, filling slots 1/4 to 4/4 with actual photo thumbnails. No simultaneous four-camera streams.
- Each shot gets **its own actual capture timestamp** and the best valid location snapshot for that shot. Do not stamp all four with the final collage export time.
- Support retaking a specific slot, cancellation and restoration/cleanup of partially captured session data.
- On completion, compose a 2×2 collage from the actual final watermarked images. Consistent crop policy, sensible spacing, correct rotation, no stretched images; provide a clear final preview.
- Save **four individual watermarked JPEGs plus one collage JPEG** (five files total) with predictable names and no duplicates. Make these easy to find/share in Rcam Gallery and external Gallery.
- Consider metadata of the collage separately: it spans four captures, so do not misrepresent one capture timestamp as the time of all four. If a collage-level footer is used, label it as creation time or capture interval.
- Process collage asynchronously; preserve responsiveness and allow user feedback on saving/error states.

## 5. End-to-end workflows to implement

**Opening store, single shot:** open Rcam → swipe to Timestamp → permission prompt once if needed → current location/address resolves or honest unavailable state → choose existing LUT via ✨ → compose photo with live watermark → shutter → open external Gallery → image has LUT and permanent timestamp, correct GPS EXIF when available.

**Closing store, four corners:** Timestamp → enable 4-Grid → shoot four different corners → see slots fill → retake one if needed → save four original watermarked photos and 2×2 collage → share collage.

**Without GPS:** user denies permission or turns off Location → camera still works, time remains accurate, UI clearly shows no location; no fabricated data.

**Offline:** GPS coordinates can work without geocoding; if address unavailable, show coordinates rather than a fake street address.

**Existing features:** Photo, Video, Motion Photo, Pro, LUT/Bingkai/Presets, normal Video LUT, flash and gallery continue working as before beneath the new UI.

## 6. Implementation guardrails

- Keep existing Camera2 photo/video/Motion Photo architecture. Integrate at actual capture/write points; do not rewrite camera engine unnecessarily.
- **Do not change normal video recording or Video LUT processing** as part of this project. Preserve Motion Photo preroll, audio, packaging and stabilization.
- Keep flash capture timing and synchronization correct; do not substitute torch for photo flash.
- All location, geocoding, JPEG rendering and collage operations must run off the main thread when potentially blocking.
- Respect Android permissions, lifecycle, scoped storage, orientation, rotation, camera switching, and no-GPS devices.
- Use real data, no dummy coordinates, fake addresses, placeholder thumbnails or fake saved images.
- No automated test suites, benchmarks, simulations, or excessive documentation requested. However, inspect code paths, fix build issues, and **do not claim real-device behavior verified without device testing**.
- If an exact visual match to a specific Timestamp Camera version is required, use the owner's actual reference screenshot(s) for comparison. Do not invent details not visible in reference.

## 7. Execution order

1. Audit Rcam UI, preview surface, capture/write pipeline and existing features.
2. Establish reusable Liquid Glass materials, layout tokens and spring animation system appropriate to the current UI framework.
3. Replace camera layout and implement Photo/Video-focused navigation, all modes and minimal Quick Controls.
4. Implement permanent ✨ Styles drawer with actual LUT/Bingkai/Presets data.
5. Implement native Timestamp mode, permission flow, GPS/geocoder states, watermark preview and full-resolution export integration.
6. Implement 4-Grid sequential capture, retake, collage, saving and gallery integration.
7. Redesign Gallery and Settings consistently without removing options.
8. Resolve compilation/integration errors; build installable APK.
9. Commit and push to current repository branch; provide APK path and concise report of implemented behavior and any remaining limitations.

## 8. Acceptance checklist (all required)

- [ ] On first launch, UI looks like a fundamentally new iOS 26-inspired camera app, not themed PhotonCamera.
- [ ] Photo and Video visually prioritized; Pro/Motion/Timestamp available via swipe.
- [ ] One shared moving glass mode indicator with real morph animation.
- [ ] ✨ permanently accessible; LUT/Bingkai/Presets open in cohesive drawer.
- [ ] Old opaque control bars and square manual control buttons gone.
- [ ] All existing camera features preserved, especially normal video, Video LUT and Motion Photo.
- [ ] Timestamp is a native Rcam camera mode, not a WebView or fake overlay.
- [ ] Location permission requested contextually; approximate/denied/GPS-off/offline states handled honestly.
- [ ] Real coordinates and available address shown; accuracy/freshness surfaced; no invented data.
- [ ] Capture time frozen per shot and visible watermark actually burned into final full-resolution JPEG.
- [ ] Watermark styling closely follows owner's reference Timestamp Camera output; fields and layout configurable.
- [ ] Saved photo in external Gallery visibly includes timestamp and compatible LUT processing.
- [ ] EXIF orientation preserved and GPS metadata written only when valid/permitted.
- [ ] 4-Grid toggle in Timestamp; four sequential shots, per-shot stamps, retake, collage and five saved files.
- [ ] UI stays responsive on Redmi 13C class hardware; no expensive decorative frame readbacks.
- [ ] APK successfully built, changes committed and pushed.

**Final instruction:** Implement real functionality and the supplied visual design. Do not stop at a plan or cosmetic pass. Do not falsely claim an exact match, tamper-proof evidence, or verified hardware performance. Report limitations clearly and deliver the APK and source changes.

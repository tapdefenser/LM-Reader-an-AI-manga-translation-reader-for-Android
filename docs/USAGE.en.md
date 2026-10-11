# LM-Reader user guide

[简体中文](USAGE.zh-CN.md) · [Project home](../README.en.md)

This guide covers `v0.1.5` and the screen translation feature in the current development build. Installation requires a signed APK; the unsigned candidate cannot be installed directly. Target devices are Android 8.0+ with arm64-v8a or x86_64.

## Library, reading and language

Select a folder on the initial path screen, choose its single/multi-chapter layout and scan it. Image folders, ZIP, CBZ and PDF share the entry point. Reselect the original folder after moving it or losing permission. Comic details offer shelf membership, categories and chapters; reader settings cover paged/strip modes, zoom, cropping and preloading.

Document paths identify comics; titles are display labels. Same titles at different paths remain separate, while rescanning the same path updates its existing record and retains shelf membership, reading progress and glossary. After scanning, the loaded page window is reconciled to recover records inserted before an existing cursor, without loading the whole library.

Open **⋮ → Translation Management** on the comic details page to manage that comic's glossary.

**Settings → General → App language** offers Follow system, Simplified Chinese and English. Follow system uses only the primary language: Simplified Chinese selects Chinese; all others, including Traditional Chinese, select English. English primary plus Chinese secondary still selects English. A manual selection overrides the system and rebuilds the UI. Titles, paths, user input and translation targets are unchanged.

## Translation, workflows and edits

Set the comic's source/target languages and workflow first. The local chain uses Seg, OCR and offline translation packs installed by direction; English-pivot routes need every required direction. For APIs, add/test the URL, model, key and protocol in **Settings → API and translation engines**. Workflow API identifiers bind to local profiles; review bindings after importing another workflow.

The details menu places **Translation options** last and glossary management third from last. API workflows offer **API (for this manga)**: follow the workflow bindings or override all API steps for this manga with one profile. Language lists place installed complete routes, including English pivots, first without choosing a language automatically. Local translation options link directly to offline pack downloads.

Long-press a manga description to select and copy its text. Expand long descriptions with **Show more** before selecting the full text. Descriptions retain their original content.

Start Cat-paw editing from a reference template and adjust loops, variables, concurrency and prompts. See the [workflow guide](WORKFLOWS.en.md). External API workflows may send images, recognized text, context and prompts to the configured provider.

## About and updates

**Settings → About** shows the installed version and GitHub project page. Automatic checks can run **On every launch**, **On the first launch each day** (default), **Every three days**, or be turned **Off**. Startup and manual **Check for updates** both open the same release-notes dialog when a newer stable release is available.

**Download update package** downloads a compatible APK in the background. Closing the dialog or reopening the app retains the transfer; About can show its progress. Once downloaded, the app checks size, published SHA-256 when available, package name, version and signing certificate before enabling **Install update**. Android handles installation and may ask you to allow this app to install updates. Manual checks remain available when automatic checks are off. Network errors and GitHub access restrictions have separate retry messages.

Queue all/selected chapters from comic details; reorder, pause, cancel, retry and inspect steps. In the reader, retranslate a page, clear translations or edit bubbles. Edits support undo/save and a leave-page prompt. Bubbles/translations are private JSON drawn over the original; source comics are not rewritten. Back up saved results before clearing them when needed.

Bubble editing supports new bubbles even on untranslated pages. Select a bubble and drag its body to move it, its bottom-right handle to resize it, or its top-right handle to rotate it. A+/A− change that bubble's font size. Each drag is one undo action. Saved geometry, rotation and font size apply to both reading and export.

Free-text translations use the whole detection frame with automatic wrapping, fitted font sizes, smaller padding and contrasting text outlines. Original-text masks retain their detection contours. Reader and export share the same layout; existing translations can be redrawn without another API request.

New defaults are 35% SEG threshold, 35% text-detection confidence, 45% free-text line merge distance, 85% mask opacity and 7% text padding. A 100% merge distance permits a gap equal to one line height (column width for vertical text), within a 0–200% range. Settings are saved per comic; changed detection parameters require recognition/retranslation to update frames. Existing saved settings and queued snapshots retain their values.

## Background tasks and notifications

Chapter translation/export use a foreground service. Switching screens, backgrounding or locking does not actively cancel queues. Separate translation and export cards show manga/chapter names, completed/total pages and unfinished chapters. Translation also shows active SEG/OCR/API counts. Each card can pause or resume its own queue and open that queue. Paused cards and completion/failure results remain after the service stops; results are dismissible. Translation pause waits for the current page to finish, retaining the existing whole-comic request rules. Resume restores paused items; failed chapters require explicit queue retry.

The translation queue menu selects **Resource utilization first** (default) or **Queue order first**. Resource priority runs independent APIs and local translation together. Waiting at any API step checks later manga; when no eligible manga remains, later pages of the same manga can be segmented ahead. Profiles on the same API/server share capacity. Queue priority completes manga in queue order. Chapter descriptions show queue progress and failures. The shelf/library footer displays current translation progress and opens the queue.

Android 13+ requests notification permission once when the first task starts. Denial does not stop work or cause repeated prompts. Use **Settings → Background tasks and notifications** to request permission again or open system settings, including a blocked task channel. Drawer progress requires notification permission.

Translation pause stops scheduling and retains the completed current-page result; a whole-comic API response can take time to finish. Export pause stops further writing and retains its snapshot. Cancel does not remove saved translations. Service termination/time limits pause tasks. Process death marks running tasks interrupted; saved results remain and need manual retry. Arbitrary workflow intermediate state is not checkpointed. Vendor power policies can still affect execution.

## Screen translation (development build)

The following describes the current development implementation and controls, which have not been released as a stable update.

The draggable translation icon on the shelf/library opens start/stop and settings. Startup warms only resources required by the selected workflow. **Text only** is the screen default: detect all text inside and outside bubbles and render text boxes, using DET without loading bubble SEG. OCR loads only recognition languages used by OCR rows, and offline translation loads only required model routes. Bubble SEG loads when bubble contours are needed. Library comic workflows use the same resource rules.

The external ball dims during loading and translation but remains draggable. Tap again after the engine is ready to submit work. The first edge drag keeps the ball visible; another outward drag tucks it away, and tapping a tucked ball only reveals it. The ball and masks hide within LM-Reader. The notification shows resources, engine start/stop and settings. Stopping continuous translation clears overlays and coverage while retaining the engine. Stopping the engine releases screen reservations without forcibly unloading shared models used by the comic queue. Stopping screen translation removes the ball.

- **Manual capture:** tap to translate the current screen; repeated taps during processing do not submit extra work. Establish the comparison baseline after drawing translations and restoring the button, then monitor with the same capture source. Detected movement clears overlays after 120 ms by default. The next page requires another tap.
- **Browse:** replaces Manga mode. Optical flow runs throughout dragging and zooming without waiting for finger release; new recognition waits for 180 ms of image stability by default and a minimum 16 capture pixels in both dimensions. Exposed areas include context and merge transitively with intersecting source regions. A complete crop from the same clean frame runs through fresh SEG/DET and OCR; its result replaces the preceding overlays together, without concatenating old OCR strings. Coverage excludes completed/in-progress regions. Bubbles are deleted only when entirely outside the screen expanded by 25% of width/height on each side. Toggle off/on to translate the current whole screen again.
- **Video:** full-screen by default, with adjustable vertical boundaries. Compare grayscale changes without waiting for stability; animated backgrounds may trigger work. Keep the preceding translation until the next result is ready to draw. Old tasks cannot replace newer published results. Tap again to stop and clear.

Browse and Video each have Select screen regions and save their current selections independently. A shared preset stores up to eight rectangles per orientation. Loading copies it into the current mode; overwriting a library preset does not silently change the other mode. Editing hides settings and displays the target app with a translucent toolbox; touches add, move, resize or delete rectangles instead of reaching the app. Finish editing to return to settings, then Apply regions. Save named presets in the toolbox; overwriting an existing name requires confirmation. An orientation with no rectangles translates the full screen. The editor requests the real orientation and releases its own request on exit; if the system ignores it, rotate the device as instructed. Tracking, change detection, recognition and rendering use only the union of selected areas. Gaps are not recorded as recognized content. Complete existing text is retained while partly outside the screen or selection, until it can be recognized completely again.

Browse and video require accessibility assistance. Startup opens accessibility settings when needed without repeatedly redirecting after a declined setup. On Android 14+, choose the target app in the system single-app capture picker. After verification against a clean window screenshot, the local stream receives raw images continuously; tracking reads small grayscale images and recognition takes a full image only when a processing slot is available. There is no video encoding or network streaming. Cancelling recording consent, or a stream that cannot be verified, falls back to clean window screenshots through one shared rate gate (at least 334 ms per request). Both continuous sources keep translations visible throughout capture. A full-display recording containing our floating button is not used for continuous translation.

Android 13 and earlier lack a portable clean continuous source; this build retains manual capture there. Manual mode can prefer window screenshots on Android 14+. Its ordinary projection fallback hides overlays at display frame boundaries only for the initial recognition capture, then monitors with translations visible. Ending projection, disabling assistance or locking the screen ends the session.

Browse tracking and translation finish current work before taking their next input. Exceeding a target frame period never cancels an estimate. Tracking uses approximately 192-pixel-wide grayscale images, balanced 8×8 corner sampling, sparse optical flow and three-parameter RANSAC. Content motion and fixed UI are fitted separately so stationary search bars do not suppress zoom estimates. The default Whole screen + per bubble option also checks free text boxes: compare original templates at candidate scales and choose content following or a fixed screen position. Deletion requires clear mismatches in two distinct valid frames; insufficient visible texture preserves the object. Whole screen omits individual checks. Weak matches trigger relocalization; older output cannot overwrite a newer merged crop. Scale smoothing uses accumulated motion so repeated tiny zoom steps are preserved. Targets are 15/30/60 FPS; actual speed depends on the device, source and image content. Optional GLES accelerates image reduction and grayscale conversion; output, orientation and complete timing are validated before enabling it, with automatic CPU fallback. Flat/repeated textures can still defeat tracking; rotation and perspective are not supported.

Next-frame preprocessing is always enabled, with at most two admitted frames and no switch. The interval starts at actual submission. The next frame requires the preceding entire frame's SEG/DET and OCR to be ready, the interval to have elapsed, an available slot and sufficient cache space. Full recognition captures stop while both slots are occupied. Unchanged-frame probes also respect the interval.

For a 200 ms interval and 500 ms translation: if first-frame recognition is ready at 120 ms, capture the second at 200 ms; if ready at 250 ms, capture then. Once both slots are full, wait for completion before checking the next deadline, then take the latest image immediately if due. Unadmitted intermediate stream frames are skipped, while admitted results remain complete. Conditional OCR or recognition depending on API output stays in workflow order; when safe early recognition cannot be established, admission conservatively waits for that frame to finish.

Screen workflow, API override, languages, appearance, recognition concurrency and cache settings are independent. API/offline translation quotas and scheduling priority are shared with the comic queue; pausing it reduces waiting. An admitted frame may exceed the cache budget and still finish completely, then block new admissions. Unpublished results are protected; failed/cancelled inputs and preprocessing survive for retry. A cache with no remaining consumer produces a blocked-cache message. Retrying changed screen content does not paint stale translations.

The workflow borrows the captured bitmap and its once-computed hash instead of repeatedly reading/decoding the page for every region. Safe OCR preparation overlaps translation of recognized regions. Settings show total elapsed time and capture, image preparation, SEG/DET, OCR, offline translation, queue and rendering times. Parallel stage totals cannot be added together; measure speed using the actual device, text count and model.

Backups include screen/local settings but exclude models and failed images. Old Manga preferences migrate to Browse and obsolete follow/preprocess options are ignored. Previously saved scope/video boundaries are preserved; new settings default to Text only and full-screen video.

## Export and failure recovery

Choose separate single/multi-chapter folders and PNG, JPEG or CBZ in export settings, then queue chapters from comic details. Translated pages are rendered with bubbles; other pages follow the selected format. PDF/ZIP inputs are supported; PDF/ZIP/WebP outputs are unavailable. Conflicting names get numeric suffixes. Completed outputs are reopened/verified before rename.

Pause, failure and interruption preserve original/translation snapshots and complete pages; later source edits do not change that retry snapshot. Reselect lost folders; restore old-folder access if an earlier temporary output needs cleanup. Failed cleanup keeps its tracking/error. **Pause exports and clean temporary files** retains unfinished resumable snapshots.

Snapshots consume private storage. Limits are 64 MB per page and 2 GB each for original/output chapter data. Free space before retrying; avoid manually deleting files used by an active task.

## Backup and migration

Backups include settings, library index, shelf, progress, glossary, translations/edits, workflows and API profiles with empty keys. Originals, models, request logs, exported files and export queues are excluded. Folder permissions cannot migrate. Reselect folders, enter keys, install models and resume manually. The current format supports schema 11.

Restoration validates the whole package, stops writers and keeps a rollback copy. Failures roll back; interrupted restoration is handled first on the next launch. If recovery is incomplete, keep app data and reopen; do not uninstall/clear data. See [backup/recovery](BACKUP.en.md).

Debug (`com.lmreader.debug`) and Release (`com.lmreader`) coexist as separate packages. Transfer test data through backup/restore into the signed Release app; this is not an in-place upgrade between them.

## Report an issue

Include app version, device/Android version, UI language, format, workflow and reproduction steps. Identify reading/translation/export/restoration as the failing stage. Share redacted logs and permitted samples, excluding keys, full private comics and unchecked backups.

## Completed tasks

Completed translation and export tasks leave their queues automatically. Saved translations, chapter completion status and published files remain; failed, interrupted and paused tasks stay available for retry or resume.

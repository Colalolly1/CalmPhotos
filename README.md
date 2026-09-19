# CalmPhotos

A photos app for an E-Ink phone (Bigme HiBreak Pro). Opens on every picture on
the phone, newest first, grouped by day; Albums and Favourites are one word away
along the bottom. Words instead of icons, no animation, no network permission.

- **Photos** — day-grouped wall, 3/4/5 across, tap to view, long-press to select.
- **Months** — jump list instead of a fast scroller.
- **Albums** — the phone's own folders, plus Favourites and Videos.
- **Viewer** — swipe, pinch/double-tap zoom, Info (date, folder, size, camera, exposure), Share, Delete (to the system bin, 30-day recovery), Play for videos.
- **Favourites** — kept locally in a plain preferences file.

Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug`

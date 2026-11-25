# DG TV

A minimal Android TV launcher for the Nvidia Shield. Black screen, white Commit Mono
text, one text size. "DG-TV", time, date and weather along the top, apps along the bottom left,
a looping animation in the bottom right.

## Using it

- **Left / right** moves along the bottom row. **OK** opens the app.
- **apps** opens the list of everything installed. **Back** returns home.
- **x** pauses or resumes the animation until you next leave the home screen.
- **DG-TV** in the top left opens the launcher's own settings (press up to reach it).
- In the all apps list, **hold OK** on an app for its options: pin (or unpin) and rename.
  Pinned apps are marked with `*`. A rename only changes what this launcher shows, and
  clearing the name puts the original back.

## Settings

Opened from **DG-TV** in the top left.

- **start paused**: when on, the animation rests on a still frame every time the home
  screen appears, until you press **x**.
- **pause at**: the frame it rests on. With this row focused, left and right move it in
  tenths of a second (hold to go faster) and the pendant shows the frame as you go.

## Making it the home screen

1. In Projectivy's settings, turn off "Override current launcher".
2. On the Shield, open Settings > Device Preferences > Accessibility, choose
   "DG TV home button" and switch it on.

Home now opens DG TV. A quick double press of Home opens recent apps. Switch the
service off in the same place to go back to the stock home screen.

## Screensaver

The app includes a screensaver that plays `app/src/main/assets/saver.mp4` full screen
on a loop. On the Shield, open Settings > Device Preferences > Screen saver, choose
"Screen saver" and pick "DG TV". The idle time before it starts is set in the same menu.

## Getting a build

Every push to `main` builds the app with GitHub Actions. The newest APK is on the
repo's Releases page as `dg-tv.apk`. Each build installs over the previous one.

## Changing things

| What | Where |
| --- | --- |
| The animation | Replace `app/src/main/assets/home.mp4` (832 x 832 canvas with the pendant centered and room around it for the flares, black background, no sound needed). A GIF or animated WebP also works: remove the MP4 and add `home.gif` or `home.webp`. |
| The screensaver video | Replace `app/src/main/assets/saver.mp4` (1920 x 1080 or 3840 x 2160, H.264 or H.265, under 100 MB, no sound needed). |
| Weather location | `WEATHER_URL` near the bottom of `MainActivity.kt` (latitude and longitude). |
| Default pinned apps | `DEFAULT_PINNED` in `MainActivity.kt`. |
| Text size, spacing, animation size | The constants at the bottom of `MainActivity.kt`. Values are the mockup's pixels divided by two. |

All of the app's code is in `app/src/main/java/dev/cameron/tvhome/MainActivity.kt`.

## Credits

- Typeface: [Commit Mono](https://commitmono.com) by Eigil Nikolajsen, SIL Open Font
  License 1.1 (see `OFL.txt`).
- Weather: [Open-Meteo](https://open-meteo.com), free for non-commercial use.

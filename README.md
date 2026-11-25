# tv home

A minimal Android TV launcher for the Nvidia Shield. Black screen, white Commit Mono
text, one text size. Time, date and weather along the top, apps along the bottom left,
a looping animation in the bottom right.

## Using it

- **Left / right** moves along the bottom row. **OK** opens the app.
- **apps** opens the list of everything installed. **Back** returns home.
- **x** pauses or resumes the animation. The choice is remembered.
- In the all apps list, **hold OK** on an app to pin it to the home row or unpin it.
  Pinned apps are marked with `*`.

## Getting a build

Every push to `main` builds the app with GitHub Actions. The newest APK is on the
repo's Releases page as `tv-home.apk`. Each build installs over the previous one.

## Changing things

| What | Where |
| --- | --- |
| The animation | Replace `app/src/main/assets/home.mp4` (640 x 640, black background, no sound needed). A GIF or animated WebP also works: remove the MP4 and add `home.gif` or `home.webp`. |
| Weather location | `WEATHER_URL` near the bottom of `MainActivity.kt` (latitude and longitude). |
| Default pinned apps | `DEFAULT_PINNED` in `MainActivity.kt`. |
| Text size, spacing, animation size | The constants at the bottom of `MainActivity.kt`. Values are the mockup's pixels divided by two. |

All of the app's code is in `app/src/main/java/dev/cameron/tvhome/MainActivity.kt`.

## Credits

- Typeface: [Commit Mono](https://commitmono.com) by Eigil Nikolajsen, SIL Open Font
  License 1.1 (see `OFL.txt`).
- Weather: [Open-Meteo](https://open-meteo.com), free for non-commercial use.

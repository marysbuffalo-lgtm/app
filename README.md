# Murtdah Fit

A private, offline Android app for fat loss, posture and daily tracking. One user, no accounts, no cloud, no analytics. Everything stays on the phone.

## What it does

- **Today:** today's session with a Start button, posture checkbox, step ring, water with quick buttons, protein, calories, sodium and the eating window countdown.
- **Guided live workout:** readiness check (green, amber, red), then warm-up, activation with ramp-up sets, main session, posture block and cooldown. GIFs, timers, rest screens, reps in reserve logging, swaps, pain flags, sounds, optional voice, vibration. It keeps running with the screen off.
- **Food:** manual meal log, favourites, drinks with calories, late eating and sweets-first flags, weekly flex meal, your own supplement list with a halal check reminder.
- **Body:** fasted weight, Friday Feelfit scan, fat mass, charts, a weekly verdict and an energy balance estimate with an error band.
- **Review:** weekly adherence and one focus for next week.
- **Reminders and widgets:** water, meals, workout, steps, eating window, protein, weigh-in and bedtime. Three home screen widgets.
- **Backup:** CSV export and import.

## Install the ready-made APK (easiest)

1. Download `app-debug.apk` (built from this repository) to your phone.
2. Open it. Android asks to allow installs from this source (your browser or Files app). Allow it, then tap Install.
3. Open Murtdah Fit.

## Build it yourself

### Android Studio

1. Install Android Studio (Narwhal or newer).
2. File, Open, choose this folder. Let Gradle sync finish.
3. Plug in your phone with USB debugging on (below) and press Run.

### Command line

```
./gradlew assembleDebug   # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # unit tests and the UI tests (Robolectric)
```

Needs JDK 17 or newer and the Android SDK with platform 36.

### USB debugging

1. Settings, About phone, Software information, tap Build number 7 times.
2. Settings, Developer options, turn on USB debugging.
3. Connect the cable and accept the prompt on the phone.
4. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Permissions to grant (Settings tab inside the app)

| Permission | Why | If you say no |
|---|---|---|
| Notifications | All reminders | No reminders. The app still works. |
| Health Connect, steps | Reads your Samsung watch and phone steps | Falls back to the phone step sensor |
| Physical activity | Phone step sensor fallback | No steps at all if Health Connect is also off |
| Alarms and reminders | On-time reminders | Reminders can arrive up to about 15 minutes late |
| Battery optimisation exemption | Stops Samsung killing reminders and widgets | Reminders and widget updates may stop in the background |

For watch steps: in Samsung Health, Settings, Health Connect, allow Samsung Health to write steps.

## Media

GIFs live in `app/src/main/assets/gifs/` and sounds in `app/src/main/assets/sfx/`. `GIFS_NEEDED.md` lists every exercise and the exact filename. 36 GIFs are included. Missing ones show a clean placeholder with the written cue. Missing sounds are replaced by generated tones. Settings, Media check shows what was found.

## Known limitations (honest list)

- **Not tested on a real phone yet.** The build and 68 automated tests pass, including a full app launch test and a live workout UI test, but sounds, vibration, Health Connect, notifications, widgets and the foreground service need a check on your Samsung.
- **Phone sensor steps** only count after the app first reads the sensor each day, and they miss steps when the phone is not on you. Health Connect is the better source.
- **Reminder timing:** without the exact alarm permission Android can delay reminders. Samsung battery saving can also block them unless you exempt the app.
- **Widgets** refresh right after you log something in the app and about every 15 minutes otherwise, so the step count on a widget can lag.
- **Water, steps and protein reminders** skip themselves when you are on track. This keeps the total at or under 8 a day.
- **The weekly review** is a screen, not a Saturday notification.
- **`.mp4` exercise files** are detected by the media check but not played. Use `.gif` or animated `.webp`.
- **Three exercises have no GIF:** tempo squat, single-arm floor press (shows the two-arm GIF with a label) and side plank. Six warm-up and activation drills also use placeholders.
- **Calorie burn** without a watch figure is an estimate (base plus steps), plus or minus 10%. Trust the 7 day weight trend more.
- **Bathroom scale body fat** readings move with hydration. Verdicts use 7 day averages and warn when body water is under 47%.
- **The program** is defined in code (`domain/Program.kt`). Settings let you swap days, change targets, rest times and reminder times, but not edit exercises in the app.

## Safety rules built in

- Calories never below 2,000 kcal and the protein floor never below 150 g, even if you type lower.
- No overhead pressing anywhere. Training to failure is never prescribed. Logging 0 reps in reserve shows a reminder to stop 1 to 2 reps short.
- Chest pain, unusual breathlessness, dizziness or palpitations block training and show: "Do not train. If it continues or is severe, seek medical care."
- Caffeine advice is capped at 100 to 150 mg a day, none after 14:00.
- Nothing in the app recommends non-halal products. Supplements are your own list, and each new one shows "Check halal certification" until you confirm it.

## Project layout

```
app/src/main/java/com/murtdah/fit/
  domain/   pure Kotlin: program, progression, readiness, session planner and state machine, reminders, body maths
  data/     Room database, settings, step reading, CSV
  service/  foreground workout service and live session state
  media/    GIF catalogue, sounds, haptics, voice
  work/     notifications, alarms, background jobs
  widget/   Glance widgets
  ui/       Compose screens
app/src/test/  unit tests and Robolectric UI tests
```

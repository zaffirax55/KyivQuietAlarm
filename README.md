# ТИХО · Київ — Android alarm v8.0

An Android alarm clock for Kyiv with accurate minute selection and a quiet-until-all-clear mode.

Features:
- Dark, adaptive native interface; hour and minute picker in 24-hour format.
- Weekday selection (Mon–Sun) and next scheduled wake-up time in Europe/Kyiv.
- Four original built-in melodies (Рассвет, Мечта, Импульс, Космос) plus Android system alarm sound picker. Preview selected tones and set in-app volume.
- Optional vibration while ringing.
- Snooze for 5 / 10 / 15 minutes; stop and snooze buttons on ringing notification.
- Manual Kyiv alert-status check; scheduled status check when alarm goes off.
- During active air alert, reschedule status checks about every minute; ring after the all-clear.
- If network or data source is unavailable or returns an unrecognized value, ring at normal time (fail-open).
- Re-arm after reboot, time changes and app updates.
- Official emergency warnings and sirens are **never muted** by this application.

Official status: https://kyiv.digital/open-api/docs/air-alert.html
GET https://kyiv.digital/open-api/air-alert/state  Accept: application/json

**Important:** This is a personal alarm clock, not an emergency/safety app. Actual polling intervals may be delayed by Android battery restrictions, airplane mode or connectivity. Test it before relying on it as your only alarm. Xiaomi/Poco: grant exact alarm, notifications, and allow unrestricted battery background activity.

## Build
GitHub Actions -> "Build Android APK" -> successful run -> artifact `KyivQuietAlarm-debug-apk` -> extract APK. It is a debug-signed sideload build.

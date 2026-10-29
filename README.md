# KyivQuietAlarm
Android alarm clock for Kyiv. At the scheduled time it checks the city's current air-raid alert via the Kyiv Digital dataset. If alert active, it rechecks every 60 seconds; when clear, it rings. If status cannot be checked, it rings at the scheduled time (fail-open).
Emergency alert notifications are never disabled. Do not use this untested application as your only alarm.

Build: GitHub Actions > Build Android APK > latest successful run > artifact KyivQuietAlarm-debug-apk. On Xiaomi/Poco, allow exact alarms, notifications and unrestricted battery activity.

City data: https://data.kyivcity.gov.ua/dataset/statystyka-povitrianykh-tryvoh-u-misti-kyievi-dep-municipal

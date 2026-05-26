# Privacy Policy — Plymouth Bins (unofficial)

_Last updated: 2026-05-26_

Plymouth Bins is an unofficial Android app that displays Plymouth City Council bin
collection schedules. It is not affiliated with, endorsed by, or operated by
Plymouth City Council.

## Data collected

The app stores the following **on your device only**, in private app storage:

| Data | Purpose |
|---|---|
| Postcode you enter | Look up addresses via the council's public address-search endpoint |
| Selected UPRN (property ID) | Fetch bin collection dates for your property |
| Address label | Display in the app UI |
| Bin collection schedule (dates, waste types) | Display upcoming collections and schedule local notifications |
| Notification settings (per-waste-type toggles, time, snooze state) | Honour your local notification preferences |
| Transient session credentials (`sid`, `csrf`, cookies for ~25 min) | Authenticate subsequent requests to the council site without re-bootstrapping |
| App log ring buffer (500 lines, in-memory) | Local debugging in the in-app Log screen — never transmitted |

The app uses no analytics, no advertising SDKs, no crash reporters, and no
third-party trackers.

## Data sent off-device

The app contacts only:

1. `plymouth-self.achieveservice.com` — the council's public bin-day lookup form.
   It receives your postcode and UPRN solely to return the address list and the
   collection schedule for that property. The app uses the same HTTP endpoints
   the council's own web form uses.
2. `api.github.com/repos/damonroberts95/plymouthBinApp/releases/latest` — used
   **only in the GitHub-distribution build** to check whether a newer APK is
   available. Sends only the request from your device's IP; no identifiers.
   This update channel is **disabled in the Play Store build**.

No data is sent to the developer or to any other server.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Fetch bin schedule from council site |
| `POST_NOTIFICATIONS` | Show local reminders for upcoming collections |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Fire collection reminders at the configured time even under battery optimisation |
| `RECEIVE_BOOT_COMPLETED` | Re-arm pending collection reminders after a reboot |

No location, contacts, storage, camera, microphone, or other personal-data
permissions are requested.

## Data retention and deletion

All data lives in app-private storage. Uninstalling the app deletes it. The
in-app **Change address** flow clears the previous UPRN. You can also clear
storage via Android **Settings → Apps → Plymouth Bins → Storage**.

## Children

The app is not directed at children and does not knowingly collect data from
anyone.

## Contact

For privacy questions or data requests, open an issue at
<https://github.com/damonroberts95/plymouthBinApp/issues>.

## Changes

If this policy changes, the updated version will be committed to the project
repository and the **Last updated** date at the top of this page will be
revised.

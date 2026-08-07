# Privacy Policy

**Ogesture** — last updated 7 August 2026

Ogesture does not collect, store, transmit, or share any personal data. Everything the app does happens locally on your device.

## Data we collect

None. The app has no analytics, no crash reporting, no advertising, and no user accounts. It does not request the `INTERNET` permission, so it cannot send anything anywhere.

## Accessibility service

Ogesture runs an Android accessibility service. Android reserves the Back, Home, and Recents actions for accessibility services, so this is the only way the app can perform them when you swipe in from a screen edge.

The service is configured with `canRetrieveWindowContent="false"`. It cannot read the content of your screen, text you type, passwords, or what you tap. It receives only window-state-changed events, from which it uses the package name of the app currently in the foreground — solely to keep gestures switched off inside apps you have added to the compatibility exclusion list. That package name is held in memory while the app runs and is never written to disk or sent off the device.

You can turn the service off at any time in **Settings › Accessibility › Ogesture**.

## Other permissions

- **Display over other apps** — draws the invisible edge strips that detect your swipes.
- **Unrestricted battery usage** — stops the system from killing the gesture service in the background.
- **Post notifications** — shows the ongoing notification Android requires for the foreground service.
- **Vibrate** — haptic feedback when a gesture triggers.
- **Receive boot completed** — restarts the gesture service after a reboot.
- **Package visibility** (`<queries>`) — lists launchable apps for the compatibility picker and keyboards so they are ignored during foreground tracking. Ogesture does not request `QUERY_ALL_PACKAGES`.

## Data stored on your device

Your preferences — whether gestures are on, and the list of apps you excluded — are stored in the app's private storage on your phone. They never leave the device and are removed when you uninstall Ogesture.

## Children

Ogesture is suitable for all ages and collects no data from anyone, including children.

## Changes

If this policy ever changes, the updated version will be published at this address and the date above will be revised.

## Contact

Questions: open an issue at <https://github.com/tanujnotes/Ogesture/issues>.

# CRAB ECT Bridge

An opt-in Android 10 bridge that listens for CRAB Car Scanner's existing `CRAB_DATA` broadcasts and forwards PID `0105` (engine coolant temperature) to the K706 SystemUI `TEMP_STRING` receiver. It does not open a Bluetooth, OBD, CAN, or MCU connection.

## Current behavior

- The service is off by default. Enabling it starts a foreground service and sends CRAB one metadata-only `get_items` request. It never sends `try_connect`, `get_all_pid`, or ECU commands.
- It locates PID `0105` from CRAB's `ITEM` metadata instead of assuming the item index.
- It accepts only PID `0105` samples with CRAB status `0`, converts Fahrenheit metadata to Celsius, and rejects out-of-range or unknown-unit readings.
- It forwards `🌡️ NN°C` or `🌡️ --°C` through `com.qf.vehicle.action.TEMP_STRING` to `com.android.systemui`.
- It holds the last valid reading during brief CRAB interruptions, then shows `--°C` after 20 seconds of disconnection or missing ECT samples.
- If the bridge can receive the OEM's own temperature broadcast, it remembers that text for disable/restore and reasserts fresh ECT after that update. If the OEM targets SystemUI so the bridge cannot observe it, disable clears the slot instead.
- The opt-in setting survives reboot. A boot receiver restarts the bridge only if it was enabled.
- A low-importance foreground notification is required for service persistence and may create a small notification/status icon.

The status-bar slot is the OEM `OutsideTempView`, positioned after the clock in the inspected SystemUI layout. The bridge does not edit SystemUI. QF Vehicle and SystemUI can still be affected by firmware-specific behavior; confirm rendering and any competing temperature feed during the head-unit test.

## Build

Requirements: JDK 17, Android SDK Platform 35 and Build Tools 35.0.0, and Gradle 8.9.

```sh
gradle --no-daemon assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Head-unit test procedure

Run this while parked. First verify CRAB is already connected and its own ECT reading is live. Install the APK manually, open **CRAB ECT Bridge**, inspect the diagnostic status, then tap **Enable bridge**. Compare the SystemUI temperature with CRAB's PID `0105`; check it from AGAMA and YouTube Music. Confirm that battery voltage, clock, Bluetooth, Wi-Fi, navigation, and the other indicators remain visible.

Then, still parked, check behavior when CRAB reports ECU disconnected and after CRAB stops producing ECT samples. The bar should keep the last value for up to 20 seconds, then show `--°C`. Reconnect CRAB through its normal app flow; the bridge should resume after fresh metadata/sample broadcasts. Check actual sample cadence: the bridge updates immediately for each CRAB sample, so it cannot make CRAB's own PID polling faster than CRAB provides data.

After installation testing, enable the bridge and reboot only when convenient to verify boot recovery. If the OEM background manager stops the service, add the bridge to the supported awake/sleep allowlists only after reviewing that configuration change.

## Rollback

Open the bridge and tap **Disable bridge**. It restores the most recently observed OEM temperature text, or clears the slot if no OEM temperature broadcast was observed. Then uninstall **CRAB ECT Bridge**. No CRAB, AGAMA, SystemUI, firmware, or vehicle configuration is modified by building or installing this app.

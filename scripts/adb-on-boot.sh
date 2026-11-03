#!/system/bin/sh
# QF K706: restore developer options and root ADB after Android has booted.
WAITED=0
while [ "$(/system/bin/getprop sys.boot_completed)" != "1" ] && [ "$WAITED" -lt 240 ]; do
    /system/bin/sleep 2
    WAITED=$((WAITED + 2))
done
if [ "$(/system/bin/getprop sys.boot_completed)" != "1" ]; then
    /system/bin/log -t AdbBootFix "timed out waiting for sys.boot_completed"
    exit 1
fi

/system/bin/settings put global development_settings_enabled 1
/system/bin/settings put global adb_enabled 1
/system/bin/setprop persist.adb.tcp.port 5555
/system/bin/setprop service.adb.tcp.port 5555
/system/bin/setprop persist.sys.usb.config adb
/system/bin/setprop sys.usb.config adb

# Start the bridge only when its saved preference is enabled.
ECT_PREFS=/data/user/0/com.rashidjanahi.crabectbridge/shared_prefs/crab-ect-bridge-state.xml
ECT_COMPONENT=com.rashidjanahi.crabectbridge/.EctBridgeService
if [ -r "$ECT_PREFS" ] && /system/bin/grep -q 'name="bridge-enabled" value="true"' "$ECT_PREFS"; then
    /system/bin/sleep 8
    ECT_ATTEMPTS=0
    ECT_RUNNING=0
    while [ "$ECT_ATTEMPTS" -lt 3 ]; do
        if /system/bin/am start-foreground-service --user 0 -n "$ECT_COMPONENT"; then
            /system/bin/sleep 3
            if /system/bin/dumpsys activity services com.rashidjanahi.crabectbridge 2>/dev/null \
                    | /system/bin/grep -q 'com.rashidjanahi.crabectbridge/.EctBridgeService'; then
                ECT_RUNNING=1
                break
            fi
        fi
        ECT_ATTEMPTS=$((ECT_ATTEMPTS + 1))
        /system/bin/sleep 3
    done
    if [ "$ECT_RUNNING" = "1" ]; then
        /system/bin/log -t EctBridgeBoot "bridge service started"
    else
        /system/bin/log -t EctBridgeBoot "failed to start bridge service after 3 attempts"
    fi
else
    /system/bin/log -t EctBridgeBoot "bridge preference is disabled or app data is unavailable; skipping"
fi

/system/bin/setprop ctl.restart adbd
/system/bin/log -t AdbBootFix "Developer options, ADB, TCP port 5555, and bridge startup handled"

# BYD cloud connectivity over Wi-Fi

## What this first version does

The Network page includes an opt-in **Keep cellular data available on Wi-Fi** switch. With an authorized ADB connection, OverDrive sets Android's standard global setting:

```sh
settings put global mobile_data_always_on 1
```

Before changing it, OverDrive saves the exact previous value (`0`, `1`, or unset). Turning the feature off restores that value. The feature is off by default.

The switch does **not**:

- turn the mobile-data master switch on or off;
- edit APNs or change SIM/operator configuration;
- write BYD vendor properties or restart the modem/telephony service;
- force BYD applications to use a particular Android `Network`;
- install, configure, or provide a VPN;
- change the vehicle's cloud region, account, or server selection.

The Android setting asks the framework to keep cellular data available while Wi-Fi is connected. It can help on firmware that honors the standard Android behavior, but it is not a guaranteed fix for BYD's separate function-data connection. Keeping the cellular bearer available may increase standby battery use or mobile-data consumption.

## Function APN status

The page reads `net.lte.apn3.state` and `net.lte.apn3.iface` with `getprop`. These reads are diagnostic only. The properties are vendor-specific and may be absent, renamed, or stale on other BYD models and firmware versions.

If `apn3` reports `disconnected` while Wi-Fi is enabled, the head-unit firmware may be explicitly tearing down the BYD function APN. The Android `mobile_data_always_on` setting cannot override that vendor telephony policy. OverDrive does not attempt root-only changes, undocumented property writes, APN edits, modem restarts, or service manipulation.

This is the limitation reported for affected firmware in [OverDrive issue #378](https://github.com/yash-srivastava/Overdrive-release/issues/378). The UI must never describe an unavailable APN status as connected or imply that the cloud is online.

## Wi-Fi and regional VPN setup

1. Connect the head unit to a Wi-Fi network with internet access.
2. If the vehicle's BYD cloud backend rejects connections from the current region, configure a trusted VPN on the Wi-Fi router and select the region required by that vehicle's cloud backend. Some newer deployments require a Chinese public IP address.
3. Prefer a router-level VPN: it applies to the car's Wi-Fi traffic independently of OverDrive and remains active when the OverDrive app is closed.
4. Enable the OverDrive switch and refresh the status. Check the APN3 line, then verify the BYD phone app, remote commands, NFC key and OTA behavior separately.
5. If BYD cloud remains offline and APN3 is disconnected, turn the switch off to restore the original Android setting. On affected firmware, Wi-Fi may still cause APN3 to be disconnected; a router VPN alone cannot repair a vendor-side bearer shutdown.

OverDrive does not ship VPN credentials or choose a VPN provider. A VPN app running on the head unit is an alternative network setup, but it only protects traffic while that app and its tunnel are running and may conflict with another Android VPN.

## Safety and rollback

- ADB is required to read and change the Android global setting.
- The feature is user-initiated and disabled by default.
- The original value is saved locally before the first change and restored when the switch is turned off. The existing parked mobile-data keep-alive feature may independently re-assert this same Android flag during an active parked session.
- No mobile-data master switch, Wi-Fi radio, APN, modem, vehicle ECU, or BYD vendor property is changed.
- The feature performs no background polling and no automatic Wi-Fi/cellular switching.
- Disable the feature before uninstalling OverDrive. Android's global setting is system-wide and survives app removal.

## Manual verification

On a compatible head unit, with ADB authorized:

1. Record `settings get global mobile_data_always_on` before enabling.
2. Enable the switch and confirm the value becomes `1`.
3. Confirm `settings get global mobile_data` is unchanged; OverDrive must not enable or disable the mobile-data master switch.
4. Read `getprop net.lte.apn3.state` and compare it with the status shown in the app.
5. Disable the switch and confirm the original value is restored (including an unset value, if it was unset initially).
6. Verify BYD cloud features on the vehicle and phone. The Android setting and APN property alone do not prove that the BYD cloud session is authenticated or online.

No vehicle-specific test has been performed as part of this contribution preparation. A 2024 BYD Dolphin is the reference vehicle; the implementation does not hard-code a model identifier, but the APN3 property may not exist on every BYD model or firmware.

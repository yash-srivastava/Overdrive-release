# Service Mode: four additional pause controls (maintainer integration patch)

The public `main` branch does **not** currently contain the Service Mode source
files, even though PR #830 was merged and the shipped APK includes the feature.
The merge message says that the reviewed feature reaches `main` with a later
release sync. For that reason this proposal deliberately **does not** add the
original Service Mode implementation to `main`.

## Requested scope

Add four choices to **Service Mode → Pause OverDrive features**:

- **Proximity** (`remote-access.html`): pause automatic phone/keyfob lock/unlock;
  retain trusted devices and related settings. This is not the separate
  radar-based proximity-recording feature.
- **RoadSense** (`road-sense.html`): suspend road hazard detection and warnings;
  retain the saved enabled setting.
- **Widgets** (`widgets.html`): suspend the Glass Widgets overlay while retaining
  layouts and preferences.
- **Seat Positions** (`seat-positions.html`): reject OverDrive-initiated
  position changes and captures without deleting saved positions; leave reads
  available.

The companion patch `service-mode-four-pauses-source.patch` contains the actual
**16-file** implementation against the reviewed Service Mode baseline at
[`6ff408710d31bf102abfff55897672638a31a750`](https://github.com/yash-srivastava/Overdrive-release/pull/830/commits/6ff408710d31bf102abfff55897672638a31a750).
It includes the four checkboxes, English/Hebrew localization, saved selections,
server validation, runtime pause gates, restore/reboot behavior and documentation.
It does not reintroduce the original Service Mode source. The maintainer must
reconcile it against the **actual source used to produce the closed-source APK**;
that source may have diverged from the reviewed commit.

## Apply to the existing Service Mode source (not public main)

From a clean working tree containing Service Mode's source files:

```sh
git apply --check --binary --whitespace=error docs/service-mode-four-pauses-source.patch
git apply --binary --whitespace=error docs/service-mode-four-pauses-source.patch
```

The maintainer can alternatively use the [four-feature-only source patch](service-mode-four-pauses-source.patch)
on a private/release branch, then port/cherry-pick it when the feature source
is published. **Do not** apply this inner patch to current public `main`: it
references Service Mode files that have not landed there.

SHA-256 (raw UTF-8 bytes of the inner patch): `656163d000de609706539edcf13d484e54358638d2e8ad17cacfd8fdfc0379d9`.
The Hebrew localization in this patch is UTF-8, for example **השהיית** and **ווידג׳טים**.

## Verification and status

The source patch was checked with `git apply --check` against the reviewed
Service Mode tree and includes no full-feature reintroduction. The packaging
patch is checked against a clean clone of public `main`.

**This PR packages an integration patch; merging these two documentation files
alone does not change the app or the APK.** Maintainer integration, Android
builds, and physical-car verification are still required.

Related: [PR #830](https://github.com/yash-srivastava/Overdrive-release/pull/830).

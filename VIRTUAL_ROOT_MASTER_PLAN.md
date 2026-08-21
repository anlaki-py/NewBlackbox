# Virtual root compatibility master plan

## Goal

Add an opt-in virtual root environment to NewBlackbox that allows Android-DataBackup to back up and restore applications installed inside BlackBox.

The first supported client is Android-DataBackup with package name `com.xayah.databackup`. The implementation must work for every ordinary app or game installed in BlackBox. It must not contain rules for Instagram or any other individual target package.

Virtual root means authority over BlackBox-managed packages and files. It does not grant Linux UID 0 on the phone and must never expose private files belonging to real Android applications.

## Success criteria

The work is complete when Android-DataBackup running inside BlackBox can:

1. Detect virtual root through libsu 6.x.
2. Bind its `RootService` without Magisk, KernelSU, APatch, or device root.
3. List packages for every BlackBox virtual user exposed to it.
4. Back up base APKs, split APKs, credential-encrypted data, device-encrypted data, external app data, OBB files, and app media.
5. Restore those categories to the selected BlackBox virtual user.
6. Install restored APK sets through the BlackBox package manager.
7. Stop a virtual package before backup and restore.
8. Preserve the metadata BlackBox needs to present correct virtual ownership and permissions after restore.
9. Refuse access to real application data, host-only files, system partitions, device nodes, kernel controls, and mount namespaces.
10. Remain disabled by default and require explicit user approval.

The first release may omit Android ID, runtime-permission, and app-operation restoration if APK and file-data backup and restore work reliably. Those items remain required for full compatibility.

## Non-goals

This project will not:

- Root the physical Android device.
- expose the phone's real `/data`, `/system`, `/vendor`, `/proc`, or `/sys` contents.
- run arbitrary commands as the real root user.
- support Magisk modules, KernelSU modules, APatch modules, kernel modification, mounts, or SELinux policy changes.
- export hardware-backed Android Keystore keys.
- promise that restored apps retain sessions tied to device hardware or non-exportable keys.
- provide unrestricted virtual root to unknown applications in the first release.

## Security rules

These rules are architectural requirements, not optional hardening work.

### Default deny

Virtual root is off by default. Enabling it requires a user-facing warning and confirmation. The first release grants access only to the approved Android-DataBackup package and expected signing certificate.

Package-name checks alone are insufficient because another APK can reuse a package name. Store the approved certificate digest in one compatibility policy and reject missing, changed, or multiple unexpected signers.

### Keep the physical UID unprivileged

Every virtual-root process continues to run under the NewBlackbox host UID. UID 0 exists only in the view presented to the approved guest. No implementation step may call the device's real `su` executable.

### Restrict paths at the trusted boundary

The trusted service must canonicalize every source and destination before opening, copying, deleting, renaming, archiving, or restoring it.

Allowed roots are derived from `BEnvironment`, never accepted from the guest:

- Virtual APK storage
- Virtual credential-encrypted app data
- Virtual device-encrypted app data
- Virtual external app data
- Virtual OBB storage
- Virtual app media
- A dedicated backup exchange directory

Reject:

- `..` traversal
- absolute paths outside the allowlist
- symlinks escaping an allowed root
- hard links to files outside an allowed root
- bind mounts and mount requests
- `/proc`, `/sys`, `/dev`, real `/data`, and real package paths
- archive entries with absolute paths or traversal components

Validate archive entries again during extraction. A safe request can still contain a malicious archive.

### Separate policy from execution

The guest-facing compatibility code never opens privileged paths directly. It sends typed requests to a host-owned service. That service applies authorization, path resolution, package checks, and operation limits before touching files.

Avoid a general API that accepts an arbitrary shell string. Parse the limited libsu control protocol at the edge, then convert recognized operations into typed service calls.

### Audit without leaking data

Record approval, denial, start, stop, package install, backup, restore, and destructive file operations. Logs may include the caller package, virtual user, operation, target package, result, and duration. Do not log file contents, authentication tokens, database rows, backup passwords, or raw command streams.

## Architecture

The implementation has five parts.

### 1. Compatibility policy

A central policy decides whether a virtual package may request virtual root and which capabilities it receives.

Suggested package:

```text
Bcore/src/main/java/top/niunaijun/blackbox/root/
```

Suggested classes:

- `VirtualRootPolicy`
- `VirtualRootGrant`
- `VirtualRootCapability`
- `VirtualRootCaller`

The policy owns package and certificate matching. Other components must not repeat allowlist logic.

Initial capabilities:

- `LIBSU_SHELL`
- `LIBSU_ROOT_SERVICE`
- `LIST_VIRTUAL_PACKAGES`
- `READ_VIRTUAL_APK`
- `READ_VIRTUAL_APP_DATA`
- `WRITE_VIRTUAL_APP_DATA`
- `INSTALL_VIRTUAL_PACKAGE`
- `STOP_VIRTUAL_PACKAGE`
- `READ_VIRTUAL_PERMISSION_STATE`
- `WRITE_VIRTUAL_PERMISSION_STATE`

### 2. Libsu shell bridge

Libsu first executes `su --mount-master`, then falls back to `su`. It expects a persistent interactive shell with standard input, output, error, exit markers, and a root identity response.

Intercept process creation only for an approved caller and only when the executable resolves to `su`. Unknown callers keep current behavior.

The bridge must:

- accept `su` and `su --mount-master`
- provide persistent standard streams
- report a virtual UID of 0 to libsu's identity check
- support `exit` and command status framing
- ignore or reject `nsenter --mount=/proc/1/ns/mnt sh` while keeping the virtual shell usable
- recognize libsu's `app_process` RootService launch command
- forward ordinary supported commands to typed operations
- return a clear nonzero status for unsupported commands

Do not pass raw commands to `/system/bin/sh` with host filesystem access.

The exact process interception point must cover `Runtime.exec` and `ProcessBuilder`, which both reach Android's process implementation. Prefer one low-level interception point over parallel Java hooks that can drift.

### 3. Virtual RootService host

When libsu submits its `app_process` command, BlackBox starts a dedicated managed process for the approved guest. This process loads the guest APK and hosts the requested RootService component.

The bridge must reproduce the libsu connection behavior expected by version 6.x:

- one regular root process per guest process
- component-based service lookup
- Binder manager connection
- private session broadcast or an equivalent result delivered to libsu
- bind, unbind, stop, client-death, and process-death handling
- no daemon mode in the first release

Do not invoke libsu's `RootServerMain.main()` unchanged. It calls `ActivityThread.systemMain()`, creates a real package context, uses the real service manager, closes process streams, and assumes real root. Reproduce the required protocol inside the BlackBox process model and attach a virtual package context.

The hosted DataBackup service must receive BlackBox-proxied framework services. Direct access to the real package manager or activity manager would show host packages and break the isolation boundary.

### 4. Privileged virtual operations service

Add a host-owned Binder service with typed methods for the operations Android-DataBackup performs. Keep this interface private to Bcore.

Minimum operation groups:

#### Packages

- List virtual users.
- List installed packages for a virtual user.
- Return package information, requested permissions, version, installer, base APK, and split APKs.
- Resolve a virtual package UID.
- Check whether a package is installed.
- Install a base APK or split APK set.
- Stop a package.
- Read and change enabled state.

#### Files

- Check existence and type.
- List children.
- Read file metadata.
- Calculate recursive size.
- Create directories.
- Copy, rename, and delete within allowed roots.
- Open files through descriptors when practical.
- Create and extract archives safely.
- Read and write backup configuration files in the exchange directory.

#### Virtual metadata

- Read the virtual owner and group.
- Record restored virtual ownership.
- Record or derive virtual SELinux labels.
- Restore safe modes and timestamps.

#### Package state

- Read runtime permissions.
- Grant or revoke supported virtual permissions.
- Read and update supported app-operation modes.
- Read and restore the virtual Android ID when BlackBox owns that value.

Every method receives a verified caller token and virtual user. Never infer authorization from caller-provided package text.

### 5. Command adapters

Android-DataBackup uses bundled `tar`, `zstd`, BusyBox tools, Android package commands, and SELinux commands. Route each category deliberately.

#### Safe guest tools

Allow DataBackup's bundled archive and compression binaries to operate only on paths already mapped into approved virtual roots. Child processes must inherit the same filesystem view and restrictions as the virtual-root service.

#### Android package commands

Translate these into BlackBox package operations:

- `pm install`
- `pm install-create`
- `pm install-write`
- `pm install-commit`
- `am force-stop`
- package and activity queries used during stop checks

Maintain temporary install sessions inside the BlackBox cache. Expire abandoned sessions and remove their files.

#### Ownership commands

Translate `chown` into virtual ownership metadata. Physical files remain owned by the host UID.

#### SELinux commands

Return virtual labels for `ls -Z` or equivalent queries. Treat allowed `chcon` requests as metadata updates after validating the path and label form. Never call the host's real `chcon`, `restorecon`, `setenforce`, or policy tools.

#### Rejected commands

Reject mount, namespace, device, kernel, process-injection, and policy-modification commands. This includes `mount`, `umount`, `nsenter`, `setenforce`, `runcon`, direct device-node access, and writes to procfs or sysfs.

## Data model

### Virtual ownership

BlackBox physically stores virtual app files under one host UID. Preserve logical ownership separately.

Start with derived ownership:

- Files under a package's private data root report that package's virtual UID and group.
- Shared external roots report their expected virtual shared group.
- APK files report the virtual system owner expected by DataBackup.

Add persisted per-path overrides only if DataBackup or target applications require them. Avoid a large metadata database before there is a demonstrated need.

### Virtual SELinux labels

Derive labels from path class and package identity:

- credential-encrypted private data
- device-encrypted private data
- APK storage
- external data

Persist overrides only for labels DataBackup explicitly restores and BlackBox later exposes. These labels are compatibility metadata and do not alter the host kernel policy.

### Install sessions

Represent split APK restore as a transaction:

1. Create a session tied to caller, virtual user, and target package.
2. Accept named APK files into a private temporary directory.
3. Parse every APK and verify that package names and signing certificates agree.
4. Require one base APK.
5. Commit through `BPackageManagerService`.
6. Remove temporary files on success, failure, timeout, caller death, or restart.

## NewBlackbox gaps that must be fixed

### Split APKs

Current package information sometimes returns empty split arrays, and the install flow centers on one base APK. Modern games often require configuration and asset splits.

Add:

- split discovery and storage in `BPackage`
- correct `ApplicationInfo.splitSourceDirs` and related package fields
- copying and persistence of all splits
- class and resource loading from installed splits
- transactional split installation
- uninstall and cleanup of split files
- backup enumeration of the complete APK set

Split support is part of the backup feature, not optional polish.

### Virtual users

Map DataBackup's Android user IDs to BlackBox user IDs. Never expose real secondary users or work profiles unless their apps are actually virtualized inside this BlackBox instance.

### Direct Boot data

`BEnvironment` already defines `data/user_de`. Confirm that package installation creates and cleanup removes this directory for each virtual user. The virtual-root service must expose it independently from credential-encrypted data.

### External media

Add a canonical `BEnvironment` method for `Android/media/<package>` rather than constructing that path in compatibility code.

## Delivery phases

Each phase should land as a focused commit. CI is the validation authority for this project per repository-owner instruction.

### Phase 0: Compatibility contract and settings

Deliver:

- Virtual-root policy and capability model
- Android-DataBackup package and certificate policy
- Disabled-by-default setting
- User confirmation and revocation UI
- Audit-event structure

Exit condition:

- An approved DataBackup install can receive a grant.
- A differently signed APK with the same package name is rejected.
- Other virtual apps remain unaffected.

### Phase 1: Libsu shell detection

Deliver:

- Approved-caller `su` interception
- Persistent shell transport
- Virtual root identity response
- Safe handling of `--mount-master`
- Explicit rejection of unsupported commands

Exit condition:

- Libsu reports a root shell for approved DataBackup.
- The shell cannot read a host file outside allowed roots.

### Phase 2: RootService handshake

Deliver:

- Recognition of libsu's RootService launch command
- Dedicated virtual-root process
- Binder manager lifecycle compatible with libsu 6.x
- Bind, unbind, stop, and death handling

Exit condition:

- DataBackup's `RemoteRootService` binds and answers a harmless call.

### Phase 3: Package discovery

Deliver:

- Virtual user listing
- Installed-package listing
- Package info, virtual UID, APK paths, and storage statistics
- No leakage of real installed applications

Exit condition:

- DataBackup displays the apps and games installed in BlackBox for the selected virtual user.

### Phase 4: Read-only backup

Deliver:

- APK enumeration
- Private CE and DE data reads
- External data, OBB, and media reads
- Safe archive and compression execution
- Virtual metadata reads

Exit condition:

- DataBackup creates and verifies backups for a base-only app and a split-APK game.

### Phase 5: Restore

Deliver:

- Transactional base and split APK installation
- Safe archive extraction
- Package stop before replacement
- CE, DE, external, OBB, and media restoration
- Virtual owner and label restoration
- Failure cleanup and rollback where practical

Exit condition:

- DataBackup restores backed-up packages into BlackBox without installing them on the physical Android system.

### Phase 6: Package state

Deliver:

- Runtime-permission backup and restore
- Supported app-operation backup and restore
- Enabled-state handling
- Virtual Android ID backup and restore

Exit condition:

- DataBackup completes these optional categories without calling real privileged services.

### Phase 7: Hardening and broader libsu compatibility

Deliver:

- Resource and operation limits
- Request cancellation and timeouts
- Crash recovery and stale-session cleanup
- Compatibility checks against supported DataBackup releases
- Removal of package-specific assumptions that are not part of the security policy

Exit condition:

- Unsupported commands fail closed.
- Process death does not leave grants, sessions, or temporary archives active.

## Failure handling

- A failed archive extraction must not leave a partly restored package running.
- Restore into a temporary package-data directory where possible, then replace the destination after validation.
- Preserve the previous data until the replacement succeeds or clearly report that rollback is unavailable.
- Stop the target package before reading mutable databases or replacing files.
- Refuse restore if the archive package name differs from the selected target.
- Refuse mixed-signature or missing-base APK sets.
- Bound recursive size calculations, directory walking, command runtime, output size, and temporary storage.
- Cancel work when the requesting Binder dies.

## CI validation plan

The repository owner has requested no local test execution and direct pushes to `main`. Implementation commits must therefore include the relevant tests and rely on build CI to execute them.

CI should eventually cover:

### Unit checks

- Caller certificate policy
- Path canonicalization and traversal rejection
- Symlink escape rejection
- Command recognition and rejection
- Virtual path mapping for each data category
- Virtual UID and label derivation
- Install-session validation
- Archive-entry validation

### Android integration checks

- Libsu shell creation for approved DataBackup
- Rejection for an unapproved caller
- RootService bind and reconnect
- Package listing isolation
- Base APK backup and restore
- Split APK backup and restore
- CE and DE data round trip
- External data, OBB, and media round trip
- Caller death during backup and restore
- No real package installation
- No host-path access

### Device matrix

Prioritize:

- Android 10
- Android 12
- Android 14
- Android 15 or the newest CI-supported API
- ARM64
- ARM32 when infrastructure permits

## Observability

Use one log tag family for the feature. Include stable operation identifiers so shell, Binder, file, and package events can be correlated.

Required diagnostics:

- Policy decision and reason
- Shell session start and end
- RootService start, bind, unbind, death, and restart
- Virtual user and target package
- Operation category and result
- Rejected path category without sensitive path contents when avoidable
- Install-session state transitions
- Cleanup results

Expose a user-readable compatibility status page showing:

- Virtual root enabled or disabled
- Approved DataBackup detected or not detected
- Signature accepted or rejected
- RootService connected or disconnected
- Last failure category
- A way to revoke access and clear temporary sessions

## Documentation

Update the README and user guide when the first usable phase lands. State plainly that virtual root applies only to BlackBox-installed apps and does not root the device.

Document:

- Supported Android-DataBackup versions
- Supported backup categories
- Unsupported privileged features
- Android Keystore limitations
- How to enable and revoke virtual root
- Where backups are stored
- Recovery steps after an interrupted restore

## Implementation discipline

- Keep compatibility code in the virtual-root package instead of scattering DataBackup checks across existing proxies.
- Add public Bcore APIs only when existing managers cannot express a required operation.
- Use typed Binder requests instead of arbitrary command execution.
- Keep every commit limited to one delivery phase or one prerequisite.
- Include tests with implementation commits even though local execution is intentionally skipped.
- Treat every CI failure as a blocking implementation defect before moving to the next phase.
- Do not weaken host isolation to make a compatibility check pass.

## First implementation task

Begin with Phase 0 and the narrowest part of Phase 1:

1. Add the policy and grant model.
2. Add the disabled-by-default setting and approval UI.
3. Verify Android-DataBackup by package and signing certificate.
4. Locate the single process-creation interception point used by both `Runtime.exec` and `ProcessBuilder`.
5. Intercept only approved `su` launches.
6. Return a persistent virtual shell that satisfies libsu's identity check and rejects every command except identity, environment setup, `exit`, and the recognized RootService launch form.

Do not add file mutation, package installation, or general command support in the first implementation commit. Establish the security boundary before expanding authority.

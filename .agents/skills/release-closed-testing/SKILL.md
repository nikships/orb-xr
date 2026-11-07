---
name: release-closed-testing
description: Bump both app versions, validate and commit the release, push it, build signed bundles, and deploy XR Phone Viewer and XR Phone Server to their Google Play closed-testing or production tracks. Use when the user asks to ship or publish a new release of this repository.
disable-model-invocation: true
---

# Release to closed testing

Use this project workflow to ship both Android applications together. Read
`AGENTS.md`, `GOOGLE_PLAY.md`, and `checklists.md` before making changes.

## Release map

| App | Module | Package | Closed track | Production track | Release bundle |
| --- | --- | --- | --- | --- | --- |
| XR Phone Viewer | `app` | `io.xrphone.viewer` | `android_xr:XR Phone Viewer Alpha` | `android_xr:production` | `app/build/outputs/bundle/release/app-release.aab` |
| XR Phone Server | `companion` | `io.xrphone.server` | `alpha` | `production` | `companion/build/outputs/bundle/release/companion-release.aab` |

The version fields are:

- `app/build.gradle.kts`: viewer `versionCode` and `versionName`.
- `companion/build.gradle.kts`: server `versionCode` and `versionName`.

## Inputs

Accept these inputs from the invoking request:

- **Version name**: optional. When omitted, find the greater semantic
  `versionName` currently used by the two modules, increment its patch
  component, and apply that name to both apps. Ask the user if either name is
  not a simple semantic version.
- **Release notes**: optional. When omitted, derive one concise en-US sentence
  from the reviewed release diff and commits.
- **Separate names**: only use different viewer/server names when explicitly
  requested.

## Preconditions

1. Work from the repository root.
2. Confirm `gplay` authentication with `gplay auth status`.
3. Confirm `keystore.properties` and `upload-keystore.jks` exist without
   printing their contents.
4. Check the installed command flags with:

   ```bash
   gplay release --help
   gplay validate --help
   gplay preflight --help
   ```

   Installed CLI help is authoritative.

## Workflow

### 1. Inspect the release state

Run:

```bash
git status --short
git diff
git log --oneline -10
gplay status --package io.xrphone.viewer --pretty
gplay status --package io.xrphone.server --pretty
```

Review every tracked and untracked path. Identify the intended release change
set and preserve unrelated local or generated files.

From each `gplay status` response, use the `tracks` source even if an unrelated
vitals source makes the aggregate status `degraded`. Record the greatest remote
version code across all tracks for each package.

### 2. Calculate and apply versions

For each app independently:

```text
next versionCode = max(local versionCode + 1, greatest remote versionCode + 1)
```

Apply the requested or derived `versionName` to both modules. Edit only the four
version fields listed above during this step.

Read both files back and report the proposed viewer and server name/code pairs
before proceeding.

### 3. Validate the source release

Run the repository-required validator:

```bash
./gradlew test lint assembleDebug
```

Fix failures and rerun until successful. Do not commit a failing release.

### 4. Stage and review the commit

Run these before committing:

```bash
git status --short
git diff
git log --oneline -10
```

Stage only the intended release files. Include both app version bumps in the
same commit as the changes being released. Exclude keystores, local properties,
generated APK/AAB files, IDE state, build directories, and unrelated generated
configuration.

Then run:

```bash
git diff --cached
git status
```

Review the complete staged diff for credentials, service-account JSON, signing
secrets, pairing tokens, private keys, and unintended files. Stop if any are
present.

Use repository commit style:

- Release-only changes: `release: prepare <version> closed test`.
- Substantive feature or fix changes: summarize the user-visible change with
  the existing `feat:` or `fix:` style.

Create the commit, then confirm it:

```bash
git commit -m "<message>"
git status --short
git log -1 --oneline
```

If a hook modifies files, review and include them, then retry once.

### 5. Push the reviewed commit

Confirm the branch and upstream, then push the new commit:

```bash
git branch --show-current
git remote -v
git push
```

Verify local `HEAD` matches its upstream SHA before building release artifacts.

### 6. Build and verify signed bundles

Run:

```bash
./gradlew bundleRelease
```

Require both bundle paths from the release map to exist. Verify each:

```bash
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
jarsigner -verify companion/build/outputs/bundle/release/companion-release.aab
```

Self-signed upload-certificate and missing timestamp warnings are expected for
the upload key; an actual verification failure is blocking.

### 7. Run Google Play release gates

For each bundle run offline preflight:

```bash
gplay preflight \
  --file app/build/outputs/bundle/release/app-release.aab \
  --fail-on error \
  --output markdown

gplay preflight \
  --file companion/build/outputs/bundle/release/companion-release.aab \
  --fail-on error \
  --output markdown
```

Then run canonical readiness checks with the exact package, track, bundle, and
release notes:

```bash
gplay validate \
  --package io.xrphone.viewer \
  --bundle app/build/outputs/bundle/release/app-release.aab \
  --track "android_xr:XR Phone Viewer Alpha" \
  --release-notes "<notes>" \
  --output table

gplay validate \
  --package io.xrphone.server \
  --bundle companion/build/outputs/bundle/release/companion-release.aab \
  --track alpha \
  --release-notes "<notes>" \
  --output table
```

Require zero blocking findings. Resolve version-code conflicts before upload.
Report Console-only manual follow-ups, but use the readiness result to decide
whether the closed-test release can proceed.

### 8. Publish each closed-track release

Publish one app at a time and preserve the result of a successful release:

```bash
gplay release \
  --package io.xrphone.viewer \
  --track "android_xr:XR Phone Viewer Alpha" \
  --bundle app/build/outputs/bundle/release/app-release.aab \
  --release-notes "<notes>" \
  --skip-metadata \
  --skip-screenshots \
  --wait \
  --output table

gplay release \
  --package io.xrphone.server \
  --track alpha \
  --bundle companion/build/outputs/bundle/release/companion-release.aab \
  --release-notes "<notes>" \
  --skip-metadata \
  --skip-screenshots \
  --wait \
  --output table
```

Require `status: completed` and the expected uploaded version code from each
command.

### 9. Verify remote state

Run readiness again with the local bundle and notes, then read status:

```bash
gplay validate --package io.xrphone.viewer \
  --bundle app/build/outputs/bundle/release/app-release.aab \
  --track "android_xr:XR Phone Viewer Alpha" \
  --release-notes "<notes>" --output table
gplay status --package io.xrphone.viewer --pretty

gplay validate --package io.xrphone.server \
  --bundle companion/build/outputs/bundle/release/companion-release.aab \
  --track alpha \
  --release-notes "<notes>" --output table
gplay status --package io.xrphone.server --pretty
```

Inspect the target tracks directly. Confirm they contain the expected version
codes with release status `completed`.

## Production release (manual edit flow with R8 mapping)

Both release builds are R8-minified. `gplay release` cannot attach a
deobfuscation file, and Play rejects `gplay deobfuscation upload` for a bundle
that was committed in a previous edit:

```text
googleapi: Error 400: The App Bundle with version code <N> is added previously
- the Deobfuscation File can't be modified., failedPrecondition
```

Every production release (and any release where crash symbolication matters)
must therefore use the manual edit flow so the mapping upload lands in the
**same edit** as the bundle upload. Mapping files:
`app/build/outputs/mapping/release/mapping.txt` and
`companion/build/outputs/mapping/release/mapping.txt`.

Server (track `production`):

```bash
E=$(gplay edits create --package io.xrphone.server --output json | jq -r '.id // .editId')
gplay bundles upload --package io.xrphone.server --edit "$E" \
  --file companion/build/outputs/bundle/release/companion-release.aab --output table
gplay deobfuscation upload --package io.xrphone.server --edit "$E" \
  --apk-version <SERVER_CODE> \
  --file companion/build/outputs/mapping/release/mapping.txt --output table
gplay tracks update --package io.xrphone.server --edit "$E" --track production \
  --releases '[{"name":"<NAME>","status":"completed","versionCodes":["<SERVER_CODE>"],"releaseNotes":[{"language":"en-US","text":"<notes>"}]}]' \
  --output table
gplay edits validate --package io.xrphone.server --edit "$E" --output table
gplay edits commit --package io.xrphone.server --edit "$E" --output table
```

Viewer (track `android_xr:production`): same sequence with
`--package io.xrphone.viewer`, the viewer bundle/mapping paths,
`--apk-version <VIEWER_CODE>`, and `--track android_xr:production`.

Notes:

- `versionCodes` in the `--releases` JSON are strings, not numbers.
- `deobfuscation upload` uses `--apk-version`, not `--version-code`.
- `--dry-run` is a **global** flag: `gplay --dry-run release ...`. Placing it
  after the subcommand fails with `flag provided but not defined: -dry-run`.
- `gplay status` has no `--output` flag; use `--pretty` or pipe JSON to `jq`.
- The closed-track publish step (step 8) can keep using `gplay release`;
  switch to this manual flow when symbols are wanted on those tracks too.

## Recovery

- **Consumed or conflicting version code**: recalculate and update only the
  affected app's `versionCode`, rerun validation, commit and push the
  correction, rebuild that bundle, and retry that app.
- **One app published and the other failed**: keep the completed release,
  report the partial state, and repair/retry only the failed package.
- **Signing mismatch**: stop. Do not replace or regenerate upload keys.
- **Active Play edit**: inspect it with installed `gplay` help and commands.
  Never discard an edit blindly.
- **CLI flag mismatch**: follow the installed `--help`, not remembered syntax.
- **Aggregate `gplay status` is degraded**: if the `tracks` source is healthy,
  verify the target track directly and report the unrelated source failure.
- **Push failure**: stop before building or publishing until the intended commit
  is confirmed on the remote.

## Completion report

Return:

- Commit SHA and pushed branch.
- Viewer version name/code and closed track.
- Server version name/code and closed track.
- Source validator, bundle build, preflight, and readiness results.
- Final Play status for both releases.
- Any preserved unrelated local files or manual Console follow-ups.

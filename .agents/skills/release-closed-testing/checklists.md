# Closed-testing release checklist

## Fixed release map

| App | Version file | Package | Track | Bundle |
| --- | --- | --- | --- | --- |
| Viewer | `app/build.gradle.kts` | `io.xrphone.viewer` | `android_xr:XR Phone Viewer Alpha` | `app/build/outputs/bundle/release/app-release.aab` |
| Server | `companion/build.gradle.kts` | `io.xrphone.server` | `alpha` | `companion/build/outputs/bundle/release/companion-release.aab` |

## Repository preflight

- [ ] Read `AGENTS.md` and `GOOGLE_PLAY.md`.
- [ ] Run `git status --short`, `git diff`, and `git log --oneline -10`.
- [ ] Classify every modified and untracked path as intended release work,
      generated output, local state, or unrelated work.
- [ ] Read current local `versionCode` and `versionName` from both modules.
- [ ] Read remote track state for both packages with `gplay status`.
- [ ] Calculate each next code as greater than its local and remote codes.
- [ ] Apply the release name to both app modules.

## Source validation

- [ ] Run `./gradlew test lint assembleDebug`.
- [ ] All tests pass.
- [ ] Lint passes.
- [ ] Both debug APKs assemble.

## Commit and push

- [ ] Stage only the intended release change set.
- [ ] Both app version bumps are staged.
- [ ] `git diff --cached` reviewed in full.
- [ ] `git status` reviewed before commit.
- [ ] No keystore, service-account JSON, local properties, generated artifact,
      credential, token, or private key is staged.
- [ ] Commit message follows recent repository style.
- [ ] Commit succeeds and `git log -1 --oneline` is correct.
- [ ] Push succeeds.
- [ ] Local `HEAD` equals upstream SHA.

## Signed bundles

- [ ] `keystore.properties` exists.
- [ ] `upload-keystore.jks` exists.
- [ ] Run `./gradlew bundleRelease`.
- [ ] Viewer AAB exists at the fixed path.
- [ ] Server AAB exists at the fixed path.
- [ ] `jarsigner -verify` succeeds for both AABs.

## Google Play gates

- [ ] `gplay auth status` succeeds.
- [ ] Installed `gplay release`, `validate`, and `preflight` help inspected.
- [ ] Viewer preflight has zero errors.
- [ ] Server preflight has zero errors.
- [ ] Viewer readiness has zero blocking findings for the XR closed track.
- [ ] Server readiness has zero blocking findings for `alpha`.
- [ ] Release notes are present for en-US.
- [ ] Bundle version codes are greater than all remote Play codes.

## Publish

- [ ] Viewer release command completes with `status: completed`.
- [ ] Viewer reports the expected version code.
- [ ] Server release command completes with `status: completed`.
- [ ] Server reports the expected version code.

## Post-release proof

- [ ] Viewer target track contains the expected code with status `completed`.
- [ ] Server target track contains the expected code with status `completed`.
- [ ] Readiness rechecked using the local bundle and release notes.
- [ ] Any unrelated vitals/status-source errors are separated from track state.
- [ ] Final report includes commit, branch, versions, packages, tracks, and all
      validation outcomes.

## Recovery

- [ ] Version conflict: bump, validate, commit/push, rebuild, and retry only the
      affected app.
- [ ] Partial release: retain the completed app release and retry only the
      failed package.
- [ ] Signing mismatch: stop without changing keys.
- [ ] Active edit: inspect before changing or discarding it.
- [ ] Push failure: stop before release build or Google Play upload.

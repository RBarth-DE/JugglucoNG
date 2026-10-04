# Distribution builds and releases

`scripts/build-dist.sh` is the production distribution entry point, locally and in
Actions. Ordinary PR CI uses Gradle directly and needs no signing credentials.

```sh
scripts/restore-build-inputs.sh       # once per clean checkout
scripts/build-dist.sh phone          # primary phone
scripts/build-dist.sh phone-all      # phone + dub
scripts/build-dist.sh wear           # primary wear
scripts/build-dist.sh wear-all       # wear + wear-dub
scripts/build-dist.sh all            # canonical four-APK release set
```

Set `ANDROID_HOME` and use JDK 21+. Install the versions in `Common/build.gradle`
(or run `scripts/dist/install-sdk.sh` on a configured SDK). Initialise the
`libjuice` submodule (`git submodule update --init --recursive`). Local signing
continues to use the existing `thekeyfile`, `thepassword`, `thekeyalias`,
`thekeypassword` Gradle properties; no new local keystore is needed. Use an
absolute `thekeyfile` path. Never paste signing values into commands or logs.

Verified outputs are in `build/dist/<target>/`. Only the requested variants are
staged/reported. A failed build clears the previous distribution for that target.
Raw `Common/build/outputs/apk` can still contain older variants; do not upload
that whole directory. Each staged APK must have the pinned production certificate,
correct package/version, non-debuggable manifest, selected ARM ABIs and exact
inventoried JNI payload. Missing production signing properties fail before build;
the fallback key is also rejected by APK certificate verification.

For a local single-ABI dev build, set
`ORG_GRADLE_PROJECT_jugglucoAbi=arm64-v8a` (or `armeabi-v7a`). A release requires
both ABIs. Additional Gradle options can follow the target, e.g. `--offline
--no-daemon`. Actions does not cache signing Gradle configuration or private keys.

## One-time protected GitHub setup

The environments are configured in the repository:

- `production-signing`: **main branch only**, four signing secrets, licensing flag.
- `release-publication`: **main branch only**, approval by `ctqvva`, admin bypass
  disabled. Self approval is allowed so the sole owner can request and approve.

In [production-signing settings](https://github.com/ctqvva/JugglucoNG/settings/environments),
enter these environment secrets (never repository secrets):

| Name | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the existing production keystore, one line |
| `ANDROID_STORE_PASSWORD` | Existing `thepassword` |
| `ANDROID_KEY_ALIAS` | Existing `thekeyalias` |
| `ANDROID_KEY_PASSWORD` | Existing `thekeypassword` |

Base64 is encoding, not encryption. Enter it through GitHub's secret UI or pipe
it directly into `gh secret set --env production-signing`; do not save it in the
checkout or terminal output. The build decodes into a temporary file with private
permissions, removes it on exit, and passes the existing Gradle properties via
step-local environment variables. No signing material reaches the publication
job or artifacts.

`DISTRIBUTION_LICENSE_APPROVED=false` is already set in that environment. Change
it to `true` only after confirming redistribution rights for the JNI inputs below.
This blocks new binary uploads, including Actions artifacts. No new binary archive
or keystore was uploaded while setting up the pipeline.

## Request a signed dev build

Use **Actions → Signed dev build → Run workflow**, branch **main**, then choose
one of the five targets. Or:

```sh
gh workflow run dev-build.yml --ref main -f target=phone
```

JetFoxy has repository read access and does not need write access. The owner and
JetFoxy can post an exact `/build-dist phone` (or another target) comment on any
issue or PR. The allowlist uses immutable GitHub user IDs in
`scripts/dist/request.py`. Other comments are ignored. Comments are parsed as
JSON data; they never become shell code or supply a source ref. Even a comment
on a fork PR builds only the default branch commit, never the PR code.

Download the ZIP from the run's **Artifacts** section (7-day retention), or use
`gh run download <run-id>`. Agents acting through the owner's GitHub credentials
can use the CLI command. Workflow dispatch requires repository write access,
which is why the comment command exists for JetFoxy. No PAT or GitHub App is needed.

## Publish a release

1. Commit/merge the desired `appVersionName` and increased `appVersionCode` on main.
2. **Actions → Release → Run workflow**, branch **main**; enter the exact version
   as the tag and optionally select prerelease.
3. Approve the `release-publication` job when prompted.

The workflow snapshots the trusted main SHA; no user-supplied checkout ref is
accepted. It rejects an existing tag/release, version/tag mismatch and a
versionCode no greater than a previous release manifest. It builds all four
APKs through the shared build, verifies the downloaded set again, runs the
existing `make-update-manifest.sh`, and validates manifest contents against APKs.
The schema remains 1, with only the two phone entries; Wear stays out of the
phone updater. Names match existing releases exactly. The default release is
latest even with an `-Alpha` name, matching 1.2.2; prerelease is explicit.

Signing has `contents: read`. Only the separate approved publication job has
`contents: write`. That job creates a draft at the captured SHA, uploads the four
APKs plus `update-manifest.json`, then publishes. If an upload fails, the draft
remains hidden: inspect/remove the draft and its newly created tag before retrying.
It never overwrites existing release assets or silently retags an old version.

Protect main and require owner review for build/workflow changes; CODEOWNERS
identifies the trust-boundary files. An administrator who can edit main or the
protected environment is trusted with signing. There are no PR/untrusted triggers
on signing workflows, and environment policies reject branches/tags other than
the main **branch**, even if a modified workflow tries to request signing secrets.

## Private JNI inputs: inventory and provenance boundary

`scripts/dist/build-inputs.json` inventories **32 files / 43,099,388 bytes**:
17 `armeabi-v7a` and 15 `arm64-v8a` libraries under `Common/src/main/jniLibs`.
These are the exact ignored inputs consumed by `build-dist.sh all` to reproduce
its existing JNI payload, including legacy libraries that may no longer have an
active sensor caller. This change does not remove or reinterpret those libraries.

Every file matches byte-for-byte the corresponding entry in **all four** existing
1.2.2-Alpha APKs. Restoration downloads the already-public primary phone APK,
checks its pinned SHA-256 (`ec2d0acfe564c24623ab2e22b83a76864210c39e19e0c9ca0bd0026622abac00`),
then extracts only explicitly named library entries and checks size/hash again.
No dynamic library from dependency AARs or locally compiled `libg.so`/`libnative.so`
is extracted. `--apk <downloaded-source.apk>` supports offline restoration.
An existing different local file is never overwritten.

No private assets repo, download token, secret archive or new proprietary upload
is needed. Keep that historical release asset available: a missing or changed
source fails closed. Update the inventory in an owner-reviewed PR when legitimate
input versions change; do not silently follow the latest release. File hashes
are public metadata, not binary contents or credentials.

The ARM inventory contains `libinit`, `libcalibrat2`, `libcalibrate` (v7a only),
`liblibre3extension`, `libcrl_dp`, `libalgorithm-jni`, `libcgat-lib`, `libscannative`,
`libmarsxlog`, `libmmkv`, `libobjectbox-jni`, `libmodpng`, `libmodft2`, `libmodpdfium`,
`libjniPdfium`, `libc++_shared`, and `libgetuiext3` (v7a only). Neither x86 ABI is
part of the distribution. The redundant `src/libre3/jniLibs` source is inactive;
`src/wearSi/jniLibs` points to an empty old mobileSi directory locally. The Gradle
Sibionics vendor exclusion patterns contribute no files in the active inventory.
Public native source/submodule and Maven dependencies supply all other inputs.

**Licensing is unresolved:** the checkout does not provide redistribution grants
or exact source-app provenance for these vendor inputs. Existing public APKs prove
availability and byte identity, not permission. The licensing flag deliberately
requires the owner to resolve that decision before new APK distribution.

Two other ignored local inputs exist: `net/ICE/turnservers.local.hpp` and
`twilio.local.hpp`. They are optional credential overrides, **not required build
inputs**, and are intentionally never restored. Canonical distributions define
`JUGGLUCO_DISTRIBUTION` so native code ignores these local headers even on the
owner's checkout; users can configure their TURN server in app settings. Ordinary
local Gradle development builds retain the optional overrides. No contents or
credential hashes from these headers are recorded.

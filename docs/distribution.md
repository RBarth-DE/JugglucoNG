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
- `production-pr-review`: **main controller only**, approval by `ctqvva`, admin
  bypass disabled, **no secrets**. Approval covers one immutable PR source snapshot.
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

Both callers of `signed-build.yml` must retain `secrets: inherit`: GitHub currently
resolves the reusable job's environment secrets as empty without explicit secret
forwarding. Keep signing values in `production-signing`; its main-only policy and
the reusable job's trusted-main guard still apply. Publication remains a separate
job without the signing environment.

Set `DISTRIBUTION_LICENSE_APPROVED=true` in that environment only after confirming
redistribution rights for the JNI inputs below (the owner has enabled it).
This blocks new binary uploads, including Actions artifacts. No new binary archive
or keystore was uploaded while setting up the pipeline.

## Request a signed dev build

Use **Actions → Signed dev build → Run workflow**, branch **main**, choose
one of the five targets and optionally enter a PR number. Empty PR number builds
main. For example:

```sh
gh workflow run dev-build.yml --ref main -f target=phone
gh workflow run dev-build.yml --ref main -f target=phone -f pr_number=547
```

JetFoxy has repository read access and does not need write access. The owner and
JetFoxy can post an exact `/build-dist phone` (or another target) comment on any
issue or PR. The allowlist uses immutable GitHub user IDs in
`scripts/dist/request.py`. Other comments are ignored. Comments are parsed as
JSON data; they never become shell code or supply a source ref. A comment on a
PR requests **that PR's exact head**, including fork PRs; a comment on an ordinary
issue requests main. Closed PRs, non-main targets and merge conflicts are rejected.

For a PR build, open the run, inspect the source SHA linked in the request summary
and approval job, then choose **Review deployments → production-pr-review →
Approve and deploy**. Only the owner can approve. Review the **entire snapshot**,
including Gradle, shell/native build code, submodules and dependencies: approving
it explicitly trusts that code with the production signing key. Approval of
ordinary PR CI is separate and grants no production signing access. The workflow
definition/controller always comes from main, not the PR.

The PR must remain open at the captured head/repository; changes while waiting
or building reject the run. Request again for each revision: an old approval never
selects a newer head. The APK tests the PR tip, **not** a synthetic merge with
newer main; rebase first when integration with current main matters. The signing
job uses the existing four environment secrets and canonical `build-dist.sh`/
Gradle signing. A trusted-main verifier independently checks the production pin,
package/version, both ARM ABIs and inventoried JNI bytes before artifact upload.

Artifacts are named `distribution-pr547-phone-<full-head-SHA>` and contain APKs
plus `build-info.json` with source/controller commits and APK checksums. These
APKs **update the matching existing installation**, retaining its data; a
normal-phone APK cannot update a dub installation. Android can reject a downgrade
if the tester already has a higher versionCode; update the PR's version normally
if needed. A production-signed PR test build is installable but still needs the
relevant device/sensor testing. It is not published as a release or updater entry.

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
protected environment is trusted with signing. Ordinary PR/fork CI has no signing
secrets. Signing workflow/controller runs must come from the main **branch**;
the explicit owner gate can additionally trust an exact PR snapshot. Requesting
an APK does not grant approval authority. No signing job has `contents: write`.

## Private JNI inputs: inventory and provenance boundary

The initial `scripts/dist/build-inputs.json` inventories **32 files / 43,099,388 bytes**:
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
source fails closed. Use the lifecycle commands below when legitimate input versions change; do not
edit hashes by hand or silently follow the latest release. File hashes
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


## Update vendor library → test → release → new baseline

Keep maintaining the actual binaries in the ignored
`Common/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/` directories. Restore the full
current set before editing it; a clean/partial checkout is not an inventory source.
Review redistribution rights and provenance for every added/replaced library.

1. Add/replace/delete the desired `.so` files, then run:
   ```sh
   scripts/update-build-inputs.sh
   scripts/build-dist.sh all --no-daemon --no-configuration-cache
   ```
   The updater scans both ARM directories and computes all names, sizes and hashes.
   It checks the pinned old APK and writes **only public metadata**. For offline
   regeneration use `--apk <the-existing-pinned-source.apk>`. No binary is copied
   into Git. Inventoried libraries are automatically exempted from Gradle stripping,
   so a new filename retains its exact bytes. Run the relevant sensor/runtime tests
   as well: APK/hash checks do not prove that a changed vendor algorithm works.
2. Commit the generated inventory and any necessary code/version changes in a PR;
   bump the app version/code for the upcoming release. Keep the `.so` files ignored.
   Review the metadata diff, including removals. Deletions get automatic
   `removedFiles` entries; builds reject APKs that still contain those retired files.
3. If the pinned APK supplies every remaining file (including ordinary removals),
   Actions still works: merge and use the normal Release workflow. If a new hash
   is unavailable, the updater records `bootstrapRequired: true`. Ordinary CI audits
   that state against the real pinned APK, but signed Actions builds fail clearly;
   they never accept missing files, unpinned replacements, or fallback signing.
4. For that first changed-input release only, after the PR is merged, use a clean
   checkout at the current remote **main SHA**, with the updated ignored libraries
   still present and the existing local production signing configuration:
   ```sh
   scripts/release-local.sh                # version/tag read from Common/build.gradle
   # optional: scripts/release-local.sh --prerelease
   ```
   This owner-only command checks GitHub identity, the protected licensing flag,
   clean source/main SHA and unused/increasing version first. It runs the canonical
   `build-dist.sh all`, checks the inputs/source again, and uses the same verified
   manifest/draft/upload/publish helper as Actions. It creates no vendor archive and
   uploads only the normal five release assets. The explicit owner invocation is
   the approval for this exceptional local publication; GitHub's environment
   approval remains in place for normal Actions releases. Failed uploads leave a
   hidden draft/tag to resolve before retrying, exactly like the normal workflow.
5. After the release is published (normal Actions or local bootstrap), run:
   ```sh
   scripts/update-build-inputs.sh --baseline <published-version>
   ```
   It downloads the published primary phone APK, verifies the production certificate,
   version, both ABIs, every desired library hash and absence of removed libraries,
   then computes/pins the whole APK checksum and clears bootstrap state. It will
   not pin a draft, unpublished local APK, wrong certificate or mismatched library
   set. Commit this metadata-only change in an owner-reviewed PR. Once merged,
   clean runners restore from that checksum-pinned release again; no private repo,
   private download token, temporary binary secret or manual JSON editing is needed.

Restoration never deletes or overwrites different local binaries. For a checkout
left at an older library set, deliberately apply the vendor edit there or use a
fresh checkout before restoring. Keep removal entries until the file is explicitly
re-added by the updater; they prevent stale packaging even after rebasing.

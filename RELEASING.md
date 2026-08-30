# Releasing

LarkBatis lives in four repositories that publish to Maven Central under the
`io.github.larkbatis` namespace. This file is the runbook for all four; the
other repositories point here.

---

## One-time setup

### 1. The GitHub organisation must be `larkbatis`

Maven Central verifies an `io.github.X` namespace by checking that the GitHub
account is exactly `X`. The group id is `io.github.larkbatis`, so the
organisation has to be `larkbatis` — not `lark-batis`. The documentation site
repository must therefore be named `larkbatis.github.io`, and `mkdocs.yml`'s
`site_url`, `repo_url` and `edit_uri_template` must match.

### 2. Secrets, on each of the four repositories

| Secret | What it is |
|---|---|
| `CENTRAL_USERNAME` | The **user token** name from <https://central.sonatype.com/account> — not the portal login |
| `CENTRAL_PASSWORD` | The user token password |
| `SIGNING_KEY` | The ASCII-armoured PGP **secret** key, whole file including the header lines |
| `SIGNING_PASSWORD` | That key's passphrase |

`larkbatis-gradle-plugin` additionally takes `GRADLE_PUBLISH_KEY` and
`GRADLE_PUBLISH_SECRET` from <https://plugins.gradle.org/user/profile>. Without
them the Plugin Portal step logs a notice and skips; the Central publish still
happens.

Generate and export a signing key:

```bash
gpg --full-generate-key                       # RSA 4096, no expiry, a passphrase
gpg --list-secret-keys --keyid-format=long    # note the key id
gpg --armor --export-secret-keys <KEY_ID> | pbcopy   # -> SIGNING_KEY
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

The public key **must** reach a keyserver, or Central fails validation with
"No public key". `keyserver.ubuntu.com` and `keys.openpgp.org` are both checked.

### 3. The `maven-central` environment

Every release workflow runs in an environment named `maven-central`. It is
created implicitly on first use with no protection rules. Add required reviewers
to it if you want releases gated on approval — that is the point of it being an
environment rather than a plain job.

### 4. Enable snapshots for the namespace

On the Portal, open the `io.github.larkbatis` namespace and choose "Enable
SNAPSHOTs". Until you do, the `snapshot` job in `ci.yml` fails on every push to
`main`.

---

## Release order

The repositories depend on each other's **published** artifacts, so they release
in this order. Each step waits for the previous artifacts to be visible on
Central — minutes for the API, up to a few hours for search.

```
1. larkbatis                  annotations, runtime, processor, scanner
2. larkbatis-gradle-plugin    injects larkbatis-processor + larkbatis-scanner
   larkbatis-maven-plugin     injects larkbatis-processor
   larkbatis-spring           depends on larkbatis-runtime
3. larkbatis.github.io        docs, versioned with mike
```

The three repositories in step 2 do not depend on one another and can go in any
order, or all at once.

---

## Releasing a repository

### 1. Set the versions in `gradle.properties`

```properties
version=0.1.0
```

The three dependent repositories carry a second property naming the core version
they build against — `larkbatisCoreVersion` in the two build plugins,
`larkbatisVersion` in `larkbatis-spring`. Set it to the core version released
in step 1:

```properties
larkbatisVersion=0.1.0
```

**The release workflow refuses to run while that property still reads
`-SNAPSHOT`.** A build plugin that injects `larkbatis-processor:0.1.0-SNAPSHOT`
into a consumer's build, or a starter that depends on a snapshot, fails in
someone else's build days later — there is nothing else in the pipeline that
would catch it.

### 2. Set the coordinates in `README.md` (and `MIGRATION.md`)

Those files show people what to copy into their build. They still read
`0.1.0-SNAPSHOT` between releases:

```bash
sed -i '' 's/0\.1\.0-SNAPSHOT/0.1.0/g' README.md MIGRATION.md   # macOS
```

**The release workflow refuses to run while either file contains
`-SNAPSHOT`.** A README telling people to depend on a version Central has never
seen is the most visible way a release can be wrong, and it is the easiest step
to forget. `CHANGELOG.md` and `RELEASING.md` are excluded from that check — they
discuss snapshots on purpose.

The documentation site (`larkbatis.github.io`) carries the same coordinates in
`docs/getting-started/`, `docs/usage/` and `docs/features/`. It releases
separately, with `mike`, and is not covered by this check.

### 3. Write the `CHANGELOG.md` section

The workflow lifts everything under `## [0.1.0]` and uses it verbatim as the
GitHub Release body. **No section, no release** — the job fails rather than
publishing something with empty notes.

### 4. Rehearse

```bash
gh workflow run release.yml -f version=0.1.0 -f dry-run=true
```

This builds, tests, signs, assembles the bundle and checks that every artifact
has a signature — then uploads nothing and creates no release. It is the
cheapest way to find a missing `description`, an unsigned artifact or a javadoc
failure.

### 5. Tag

```bash
git commit -am "Release 0.1.0"
git tag v0.1.0
git push origin main --tags
```

The tag push runs the release workflow with `publishingType=USER_MANAGED`: the
bundle is uploaded and validated, and then it **stops and waits for you**. Open
<https://central.sonatype.com/publishing/deployments> and press Publish. A
release to Central cannot be taken back, so the last step is deliberately a
human one.

To skip that confirmation, run the workflow by hand with
`publishing-type=AUTOMATIC`.

### 6. Afterwards

Set `gradle.properties` and the README coordinates back to the next
`-SNAPSHOT`, and open an `## [Unreleased]` section in the changelog.

---

## What the workflow actually does

The Central Portal does not accept a deploy over the wire. It accepts one zip in
Maven repository layout, validates the whole thing at once, and only then makes
it visible. So:

1. `./gradlew publishAllPublicationsToCentralBundleRepository` writes a local
   Maven repository into `build/central-bundle` — jars, sources, javadoc, POMs,
   Gradle module metadata, checksums and `.asc` signatures.
2. `.github/scripts/publish-to-central.sh` drops the local `maven-metadata.xml`
   files (Central derives its own), asserts every artifact has a signature, zips
   the directory and uploads it as a single deployment.
3. It polls `/api/v1/publisher/status` until the deployment reaches `VALIDATED`
   (user-managed) or `PUBLISHED` (automatic), and fails the build on `FAILED`
   with the Portal's own error list.

Because a repository's modules go up as one bundle, either all of them land or
none do — there is no half-published release to clean up.

You can run step 2 locally against a bundle you already built:

```bash
./gradlew publishAllPublicationsToCentralBundleRepository -Pversion=0.1.0
.github/scripts/publish-to-central.sh --bundle-dir build/central-bundle \
    --name "larkbatis 0.1.0" --dry-run
```

---

## Snapshots

Every push to `main` publishes a snapshot to
`https://central.sonatype.com/repository/maven-snapshots/`. Snapshots are neither
signed nor validated — that is Central's rule for them, not a shortcut taken
here. To consume one:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
}
```

---

## Troubleshooting

**"No public key" during validation.** The signing key never reached a
keyserver, or has not propagated yet. Re-send it and retry; propagation is
usually minutes.

**"Missing signature for ...".** `SIGNING_KEY` is not set on the repository, or
what was exported is a public key rather than a secret one. The script checks
this before uploading and names the file.

**A POM is rejected for a missing field.** The build asserts `description` is set
on every published module; the rest of the POM (name, url, licenses, developers,
scm) comes from the shared publishing block in `build.gradle.kts`.

**The deployment is stuck in `VALIDATING`.** The workflow polls for 30 minutes
and then fails, leaving the deployment in place. Check the Portal. If it failed,
leave it there when opening a support request — the files are the evidence.

**A failed deployment is in the way.** Drop it from the Portal UI, or:

```bash
TOKEN=$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64)
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "https://central.sonatype.com/api/v1/publisher/deployment/<deployment-id>"
```

**`mvn larkbatis:check` does not resolve.** Goal-prefix resolution needs a
group-level `maven-metadata.xml` that Maven's own deploy plugin writes, and this
artifact is built with Gradle. Add `io.github.larkbatis` to `<pluginGroups>` in
`settings.xml`, or use the full coordinate. Goals bound in a POM are unaffected.

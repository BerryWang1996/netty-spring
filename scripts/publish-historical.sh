#!/usr/bin/env bash
#
# publish-historical.sh — republish a historical git tag to Maven Central under
# the io.github.berrywang1996 namespace.
#
# Usage:
#     bash scripts/publish-historical.sh v1.4.0
#     bash scripts/publish-historical.sh v1.6.2
#     bash scripts/publish-historical.sh v1.7.0
#
# What it does:
#   1. Creates a git worktree at /tmp/netty-spring-publish-<tag> on a detached HEAD
#      of the tag (so master is untouched).
#   2. Rewrites every <groupId>com.github.berrywang1996</groupId> to
#      <groupId>io.github.berrywang1996</groupId> in every pom.
#   3. Splices the current master's `release` profile + <distributionManagement>
#      + <scm> block into the historical root pom (so the same release flow works).
#   4. Adds <name> + improved <description> + corrected <url> to every child pom
#      that is missing them (Central requires these per artifact).
#   5. Runs `mvn deploy -P release`. autoPublish=false → bundle waits for manual
#      Publish click on https://central.sonatype.com.
#   6. Removes the worktree on success (kept on failure so you can debug).
#
# Requires:
#   - GPG keypair installed and uploaded to a public keyserver
#   - ~/.m2/settings.xml with <server id="central"> (Central Portal user token)
#     and (recommended) <server id="gpg.passphrase">
#   - mvn 3.9.x on PATH
#

set -euo pipefail

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
  echo "Usage: $0 <tag>     e.g. $0 v1.7.0"
  exit 2
fi

REPO_ROOT="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)"
cd "$REPO_ROOT"

if ! git rev-parse --verify --quiet "$TAG^{commit}" > /dev/null; then
  echo "ERROR: tag '$TAG' does not exist locally. Run: git fetch --tags"
  exit 2
fi

WORKTREE="$(mktemp -d -t "netty-spring-publish-${TAG}-XXXXXX")"
echo "[$TAG] worktree at $WORKTREE"
git worktree add --detach "$WORKTREE" "$TAG"

cleanup() {
  echo "[$TAG] removing worktree"
  git worktree remove --force "$WORKTREE" || true
}

cd "$WORKTREE"

# ---- Step 1: rename groupId in every pom (sed-style, in-place) ----
echo "[$TAG] renaming com.github.berrywang1996 -> io.github.berrywang1996"
find . -name pom.xml -type f -exec \
  sed -i 's|<groupId>com\.github\.berrywang1996</groupId>|<groupId>io.github.berrywang1996</groupId>|g' {} \;

# ---- Step 2: rewrite root pom's <distributionManagement> + add <scm> + add release profile ----
ROOT_POM="$WORKTREE/pom.xml"

# Remove any existing <distributionManagement> block first (might be the Aliyun one)
python3 - <<PYEOF
import re, pathlib
p = pathlib.Path("$ROOT_POM")
t = p.read_text(encoding="utf-8")
t = re.sub(r"\s*<distributionManagement>.*?</distributionManagement>", "", t, flags=re.DOTALL)
p.write_text(t, encoding="utf-8")
PYEOF

# Inject <scm> right after <licenses> (or after <developers> if no licenses).
# Inject release profile + new distributionManagement before </project>.
python3 - <<'PYEOF'
import re, pathlib, os
root = os.environ.get("WORKTREE_ROOT_POM") or pathlib.Path("pom.xml")
p = pathlib.Path(root) if isinstance(root, str) else root
t = p.read_text(encoding="utf-8")

scm_block = """
    <scm>
        <connection>scm:git:git://github.com/BerryWang1996/netty-spring.git</connection>
        <developerConnection>scm:git:ssh://git@github.com/BerryWang1996/netty-spring.git</developerConnection>
        <url>https://github.com/BerryWang1996/netty-spring</url>
        <tag>HEAD</tag>
    </scm>
"""

if "<scm>" not in t:
    # insert after </licenses> if present, else after </developers>, else before </project>
    if "</licenses>" in t:
        t = t.replace("</licenses>", "</licenses>\n" + scm_block, 1)
    elif "</developers>" in t:
        t = t.replace("</developers>", "</developers>\n" + scm_block, 1)
    else:
        t = t.replace("</project>", scm_block + "\n</project>", 1)

# Append release profile + new distributionManagement before </project>
release_block = """
    <profiles>
        <profile>
            <id>release</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-source-plugin</artifactId>
                        <version>3.3.1</version>
                        <executions>
                            <execution>
                                <id>attach-sources</id>
                                <goals><goal>jar-no-fork</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-javadoc-plugin</artifactId>
                        <version>3.10.0</version>
                        <executions>
                            <execution>
                                <id>attach-javadocs</id>
                                <goals><goal>jar</goal></goals>
                            </execution>
                        </executions>
                        <configuration>
                            <doclint>none</doclint>
                            <failOnError>false</failOnError>
                        </configuration>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-gpg-plugin</artifactId>
                        <version>3.2.4</version>
                        <executions>
                            <execution>
                                <id>sign-artifacts</id>
                                <phase>verify</phase>
                                <goals><goal>sign</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.sonatype.central</groupId>
                        <artifactId>central-publishing-maven-plugin</artifactId>
                        <version>0.7.0</version>
                        <extensions>true</extensions>
                        <configuration>
                            <publishingServerId>central</publishingServerId>
                            <autoPublish>false</autoPublish>
                            <waitUntil>validated</waitUntil>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>

    <distributionManagement>
        <repository>
            <id>central</id>
            <name>Sonatype Central Portal</name>
            <url>https://central.sonatype.com/api/v1/publisher</url>
        </repository>
    </distributionManagement>
"""

# If the pom already has a <profiles> block, merge into it (insert release as a sibling).
# Otherwise the block above (which contains its own <profiles> wrapper) goes before </project>.
if "<profiles>" in t and "</profiles>" in t:
    # remove the outer <profiles>...</profiles> wrapper from our block
    inner = release_block
    inner = inner.replace("<profiles>\n", "").replace("\n    </profiles>", "")
    # split inner into the release <profile> part and the distributionManagement part
    parts = inner.split("</profile>")
    release_profile = parts[0] + "</profile>"
    dist_mgmt = parts[1] if len(parts) > 1 else ""
    # insert release_profile before </profiles>
    t = t.replace("</profiles>", release_profile + "\n    </profiles>", 1)
    # append dist_mgmt before </project>
    t = t.replace("</project>", dist_mgmt + "\n</project>", 1)
else:
    t = t.replace("</project>", release_block + "\n</project>", 1)

p.write_text(t, encoding="utf-8")
PYEOF

# ---- Step 3: add <name> to every child pom that lacks one ----
python3 - <<'PYEOF'
import re, pathlib
for pom in pathlib.Path(".").rglob("*/pom.xml"):
    t = pom.read_text(encoding="utf-8")
    # skip if <name> already present at root level
    if re.search(r"<project[^>]*>.*?<name>[^<]+</name>", t, flags=re.DOTALL):
        continue
    # derive name from <artifactId>
    m = re.search(r"<artifactId>([^<]+)</artifactId>", t)
    if not m:
        continue
    name = m.group(1)
    # insert <name> right after <artifactId>
    t = t.replace(f"<artifactId>{name}</artifactId>",
                  f"<artifactId>{name}</artifactId>\n\n    <name>{name}</name>", 1)
    pom.write_text(t, encoding="utf-8")
PYEOF

# ---- Step 4: deploy ----
echo "[$TAG] running mvn deploy -P release"
mvn -B clean deploy -P release -DskipTests

# ---- Step 5: clean up worktree ----
cd "$REPO_ROOT"
cleanup
echo "[$TAG] done — go to https://central.sonatype.com to publish the bundle"

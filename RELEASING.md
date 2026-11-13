# Releasing

Releases are a Maven repository on GitHub Pages: the `gh-pages` branch, its `maven/` directory, served at
https://okay-effects.github.io/okay-java/maven. No account and no token is needed to use it, only a repository.
okay-java's one dependency, `io.github.okay-effects:okay-kernel-api`, is released in okay-kernel's
(https://okay-effects.github.io/okay-kernel/maven, okay-kernel's `RELEASING.md`); okay-audit, which the plugins use
for `forbid`, in okay's (https://okay-effects.github.io/okay/maven).

Released, group `io.github.okay-effects`: `okay-java`, `okay-java-resumable`, `okay-java-spring-boot`, the Gradle
plugin `io.github.okay-effects.resumable` (`okay-java-resumable-gradle-plugin` and its marker
`io.github.okay-effects.resumable:io.github.okay-effects.resumable.gradle.plugin`), and
`okay-java-resumable-maven-plugin`; each with its sources and javadoc. How to use them: the README's Install and
`resumable/README.md`'s.

## Making one

1. On `master`, everything green: `./gradlew test`, the samples
   (`./gradlew :okay-java-resumable:mavenSample`; `../../../gradlew test run` in `resumable/samples/gradle`).

2. The version: `version` in `build.gradle.kts`, `resumable/gradle-plugin/build.gradle.kts` and
   `resumable/maven-plugin/pom.xml` (the samples' too), committed, tagged (`v0.1.0`).

3. The repository written into `build/pages-maven/`: the libraries, the Maven plugin (by `resumable/mvnw`), the
   Gradle plugin and its marker:

   ```sh
   rm -rf build/pages-maven
   ./gradlew release
   ```

   No POM may name a SNAPSHOT or a `file:` repository: `grep -rl 'SNAPSHOT\|file:' build/pages-maven --include=*.pom`
   finds nothing.

4. Onto `gh-pages`, under `maven/`, beside the releases already there:

   ```sh
   # the first time: git worktree add --orphan -b gh-pages /tmp/okay-java-pages
   git worktree add /tmp/okay-java-pages gh-pages
   mkdir -p /tmp/okay-java-pages/maven
   rsync -a build/pages-maven/ /tmp/okay-java-pages/maven/
   touch /tmp/okay-java-pages/.nojekyll
   git -C /tmp/okay-java-pages add -A
   git -C /tmp/okay-java-pages commit -m "release 0.1.0"
   git -C /tmp/okay-java-pages push origin gh-pages
   git push origin v0.1.0
   ```

   The first time, GitHub Pages is turned on for the branch `gh-pages`.

5. Checked as a stranger would: a new Gradle project and a new Maven project, each with an empty local repository
   (`--gradle-user-home <temp>`, `-Dmaven.repo.local=<temp>`), resolving from Maven Central and the Pages URLs only.

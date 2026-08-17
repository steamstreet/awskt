# Repository Guidelines

## Project Structure & Module Organization
This repo hosts Kotlin multiplatform libraries grouped by AWS capability. Core modules such as `standards`, `env`, `logging`, `serialization`, and `events` expose shared code under `src/commonMain`, with platform-specific variants in `src/jvmMain`, `src/jsMain`, or `src/iosArm64Main`. Domain-specific packages live inside `lambda/` (Lambda handler frameworks), `dynamokt/` (DynamoDB client), `appsync/`, and `cognito/`, each following the same multiplatform layout. Shared build logic lives in `buildSrc/`, while published artifacts resolve from Gradle metadata in `build.gradle.kts`. Documentation and examples sit in `docs/` and module-level `README`s; keep API samples alongside their module.

## Build, Test, and Development Commands
- `./gradlew build` compiles every target and assembles artifacts.
- `./gradlew check` runs unit tests and multiplatform verifications across modules.
- `./gradlew :logging:allTests` (swap module path as needed) focuses on a single module for quicker feedback.
- `./gradlew publishToMavenLocal` builds and stages artifacts for local integration testing.

## Releasing
Run `scripts/release.sh`. `--dry-run` stops after the clean `check`, before anything is uploaded, and `--scope minor` or `--scope major` overrides the default patch bump. The script validates, uploads, tags, publishes both deployments and confirms the artifacts answer on `repo1`.

Do not release with `./gradlew final` alone. It closes the staging repository but does not publish it: the deployment stops at `VALIDATED`, this namespace is not on auto-publish, and `final` exits 0 having shipped nothing. The plugin also needs publishing separately, because `gradle-plugin` is an `includeBuild` and takes no part in the root project's release.

Artifacts publish as `com.steamstreet.awskt:<module>`, and the plugin as `com.steamstreet.awskt:gradle-plugin` under the plugin id `com.steamstreet.awskt.native-lambda`. Versions through 3.0.0 published as `com.steamstreet:awskt-<module>` instead; those coordinates remain on Central and are not maintained.

## Coding Style & Naming Conventions
Follow standard Kotlin style: 4-space indentation, `PascalCase` types, `camelCase` members, and `UPPER_SNAKE_CASE` constants. Most modules enable `explicitApi()`, so declare visibility and return types on public APIs. Keep package names under `com.steamstreet.<module>`. Gradle scripts use Kotlin DSL; align new tasks with the conventions in `buildSrc/steamstreet-common.*`. Serialization relies on `@Serializable` models; prefer data classes and meaningful property names mirroring AWS schemas.

## Testing Guidelines
Tests use the built-in Kotlin test framework (`kotlin("test")`) with platform-specific source sets such as `src/commonTest` and `src/jvmTest`. Name test files and classes with the `*Test.kt` pattern (e.g., `JsonDiffTest.kt`). Run `./gradlew :module:allTests` before submitting changes, and ensure new functionality includes coverage across relevant source sets. For concurrency-heavy features, add coroutine test dispatchers to mirror production behavior.

## Commit & Pull Request Guidelines
Author commits in clear, descriptive sentences summarizing both change and module (e.g., “Improve DynamoKt stream failure logging”). Group related edits and avoid sweeping refactors with feature work. Pull requests should describe the problem, outline the solution, list affected modules, and link to issues if available. Include testing notes (`./gradlew check`) and update docs when APIs change. Screenshots or sample payloads are encouraged when modifying logging or serialized output.

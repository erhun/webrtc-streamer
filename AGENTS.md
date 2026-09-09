# Agent Guidelines for scrcpy

This document provides guidelines for AI agents working on the scrcpy codebase, including build commands, test execution, and code style conventions.

## Build Commands

### Client (C application)
- **Debug build**: `meson setup build --buildtype=debug`
- **Release build**: `meson setup build --buildtype=release --strip -Db_lto=true`
- **Build with prebuilt server**: `meson setup build --buildtype=release --strip -Db_lto=true -Dprebuilt_server=/path/to/scrcpy-server`
- **Compile**: `ninja -C build`
- **Install**: `ninja -C build install`

### Server (Java Android application)
- **Build debug APK**: `./gradlew -p server assembleDebug`
- **Build release APK (unsigned)**: `./gradlew -p server assembleRelease`
- **Build without Gradle**: Use `server/build_without_gradle.sh` (requires Android SDK)

### Combined build (for CI)
- **Client tests**: `release/test_client.sh`
- **Server tests**: `release/test_server.sh`
- **Build server**: `release/build_server.sh`

### Cleaning
- **Client build directory**: `rm -rf build`
- **Server build directory**: `rm -rf server/build`
- **Test build directory**: `rm -rf build-test`

## Test Commands

### C unit tests
- **All tests** (debug build with sanitizers):
  ```bash
  meson setup build-test --buildtype=debug -Dcompile_server=false -Db_sanitize=address,undefined
  ninja -C build-test test
  ```
- **Single test** (e.g., `test_adb_parser`):
  ```bash
  meson test -C build-test test_adb_parser
  ```
- **Test list**: See `app/meson.build` lines 215‑281 for test executable names.

### Java unit tests (JUnit)
- **All tests**: `./gradlew -p server test`
- **Single test class**: `./gradlew -p server test --tests "*.StringUtilsTest"`
- **Single test method**: `./gradlew -p server test --tests "*.StringUtilsTest.testUtf8Truncate"`

### Lint and style checks
- **Android lint**: `./gradlew -p server lint`
- **Checkstyle**: `./gradlew -p server checkstyle`
- **Full check** (tests + lint + checkstyle): `./gradlew -p server check`

## Code Style Guidelines

### C Code Style
- **Indentation**: 4 spaces, no tabs.
- **Braces**: Function brace on same line as function name.
- **Return type**: On separate line for function declarations.
- **Naming**:
  - Functions and variables: `snake_case`.
  - Public functions prefixed with `sc_` (e.g., `sc_strncpy`).
  - Types and structs prefixed with `sc_` (e.g., `sc_socket`).
  - Macros and constants: `SC_UPPER_SNAKE_CASE`.
  - Include guards: `SC_FILENAME_H`.
- **Header includes order**:
  1. System includes (`<stdio.h>` etc.)
  2. Project includes (`"common.h"` etc.)
- **Error handling**:
  - Use `LOG_OOM()` for out‑of‑memory errors.
  - Log errors with `LOGE()`.
  - Functions returning `bool` indicate success (`true`) / failure (`false`).
  - Functions returning `int` typically follow Unix conventions (0 for success, -1 for error).
- **Pointer alignment**: `*` adjacent to variable name, not type.
- **Struct initialization**: Use designated initializers when possible.
- **Line length**: Keep lines under 150 characters (soft limit).

### Java Code Style
- **Indentation**: 4 spaces, no tabs.
- **Braces**: Same line as class/method/control statement.
- **Naming** (standard Java):
  - Classes: `PascalCase`.
  - Methods and variables: `camelCase`.
  - Constants: `UPPER_SNAKE_CASE`.
- **Imports order** (enforced by Checkstyle):
  1. Special imports (`com.genymobile.*`)
  2. Third‑party packages (`org.junit.*`, `android.*`)
  3. Standard Java packages (`java.util.*`)
  4. Static imports
- **No star imports** (except static imports).
- **Line length**: Maximum 150 characters (warning).
- **Annotation style**: `@Override` on separate line before method.
- **Error handling**:
  - Use `Ln` class for logging (`Ln.d()`, `Ln.e()`).
  - Throw appropriate runtime exceptions for unrecoverable errors.
  - Validate arguments with `requireNonNull` or manual checks.

### Checkstyle Rules Highlights
- **File length**: Limited.
- **Trailing spaces**: Prohibited.
- **Whitespace**:
  - Operators surrounded by spaces.
  - No whitespace before `;` or `,`.
  - Whitespace after `,` and `;`.
- **Blocks**: Always use braces, even for single‑statement blocks.
- **Modifiers**: Order: `public protected private abstract default static final transient volatile synchronized native strictfp`.
- **Hidden fields**: Allowed for constructor parameters and setters.
- **Todo comments**: Allowed but flagged as info.

## Development Workflow

### Branching
- `master` – latest stable release.
- `dev` – current development branch. Contributions should be based on `dev`.

### Running the application locally
- **With built client**: `./run build scrcpy-arguments`
- **Using adb directly**: `adb push` the server and execute `app_process`.

### Versioning
- Version number follows semantic versioning (e.g., `3.3.4`).
- Both client and server must have the exact same version.

## Useful Resources
- **Documentation**: `doc/` directory (especially `develop.md`, `build.md`).
- **Checkstyle config**: `config/checkstyle/checkstyle.xml`.
- **Meson configuration**: `meson.build`, `app/meson.build`, `server/meson.build`.
- **CI scripts**: `release/` directory.

## Notes for AI Agents
- **Never commit changes** unless explicitly requested.
- **Run lint and tests** before finalizing changes (use the commands above).
- **Follow existing patterns** – examine neighboring files for consistency.
- **C code changes** should compile without warnings (`-Wall -Wextra`).
- **Java code changes** must pass `checkstyle` and `lint`.
- **When in doubt**, refer to the existing codebase style.

---

*This file is intended to help AI agents navigate the scrcpy project. Update it as the project evolves.*

## Agent skills

### Issue tracker

Issues live as local markdown files under `.scratch/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default triage labels (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout. See `docs/agents/domain.md`.
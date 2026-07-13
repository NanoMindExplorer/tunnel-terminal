# CI & Unit Tests

## Local

```bash
./scripts/run-unit-tests.sh
# or
./gradlew testFullDebugUnitTest
```

## GitHub Actions

Existing workflows under `.github/workflows/` build debug/release APKs.

To enable automatic unit tests on PR, add a workflow (requires `workflow` OAuth scope
or a PAT with `workflow` permission) with:

```yaml
name: Unit Tests
on:
  pull_request:
    branches: [ main ]
  push:
    branches: [ main ]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: temurin
      - uses: android-actions/setup-android@v3
        with:
          packages: 'ndk;25.1.8937393 cmake;3.22.1'
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew testFullDebugUnitTest --no-daemon --continue
```

## Test modules

| File | Coverage |
|------|----------|
| `03-TerminalEmulatorTest` | ANSI / resize / scrollback |
| `04-AiToolCallParserTest` | tool_call parse / injection |
| `05-PermissionManagerTest` | Always/Never allow, session scope |
| `06-WaveUtilsTest` | path sandbox, width, metrics, markers |
| `07-AiToolCallAndPromptTest` | parse, read-only set, cwd prompt |

# CI & Unit Tests

**App version:** 9.3.0 (versionCode 73) · **Tests:** 27 files under `app/src/test/`

## Local

```bash
./scripts/run-unit-tests.sh
# or
./gradlew testFullDebugUnitTest

# Full debug APK (proot flavor — GitHub/F-Droid)
./gradlew assembleFullDebug

# Full release (signed; needs keystore / CI secrets)
./gradlew assembleFullRelease
```

## GitHub Actions

Workflows under `.github/workflows/`:

| Workflow | Trigger | Output |
|----------|---------|--------|
| `build-debug.yml` | push/PR main, `workflow_dispatch` | `assembleFullDebug` + artifact |
| `build-apk.yml` | push/PR main | APK build |
| `build-release.yml` | tags / manual | signed release + GitHub Release (needs secrets) |

Unit tests on PR (opsional; butuh permission `workflow`):

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

## Test modules (27 files)

| File | Coverage |
|------|----------|
| `03-TerminalEmulatorTest` | ANSI / resize / scrollback |
| `04-AiToolCallParserTest` | tool_call parse / injection |
| `05-PermissionManagerTest` | Always/Never allow, session scope |
| `06-WaveUtilsTest` | path sandbox, width, metrics, markers |
| `07-AiToolCallAndPromptTest` | parse, read-only set, cwd prompt |
| `08-HistoryAndUrlTest` | AI base URL validation |
| `09-ChatExportTest` | Chat export role formatting |
| `10-BookmarkAndTabLabelTest` | bookmarks, tab labels |
| `11-ImeDeltaTest` | IME delta / typed chars |
| `12-TerminalPolishTest` | paste, DECCKM, ExtraKeys utils |
| `13-ScrollbackSelectTest` | scrollback select/copy |
| `14-FindUrlMouseTest` | tt-find, open-url, mouse |
| `15-UnicodeLazyTest` | Unicode width, LazyColumn helpers |
| `16-FontZoomTest` | pinch zoom snap/range |
| `18-TerminalLayoutImeTest` | layout metrics, IME wipe |
| `20-TerminalWave20Test` | Wave 20 terminal polish |
| `21-SelectionHitTest` | accurate selection hit-test |
| `22-UbuntuRootfsUrlTest` | Ubuntu download URL / SHA sums |
| `23-UbuntuSessionPathTest` | SessionTargetResolver paths |
| `25-AiSkillTest` | AI Skills scope / inject |

## Built-in commands (selected)

| Command | Description |
|---------|-------------|
| `history` / `history-clear` | Tab + persisted history |
| `export-output` / `export-chat` | Transcript exports |
| `setup-storage` / `storage-*` | SAF + MediaStore Download (Wave 19) |
| `bookmark list\|add\|go\|remove` | Directory bookmarks |
| `tt-find <query>` | Search scrollback |
| `copy-output` | Clipboard terminal output |
| `ssh-list-hostkeys` / `ssh-reset-hostkeys` | TOFU SSH fingerprints |
| `ai-metrics` / `font-reset` | AI latency / font default |

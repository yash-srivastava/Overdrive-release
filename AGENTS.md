# Agent instructions (OverDrive public repo)

Read CONTRIBUTING.md first; this file only adds what coding agents get wrong.

## Build and test
- `./gradlew assembleDebug` builds the public app. `./gradlew test` runs the unit tests.
- Clone with `core.autocrlf=false` and `core.symlinks=true`: many tests assert on source/web text, and
  `app/src/main/assets/web/local/shared` is a symlink.
- Always compile after applying a change; a patch that applies cleanly can still reference symbols that do not exist here.

## What is not in this repo
- Official builds add a private overlay (`private/`, gitignored). Do not create, reference or "restore" it.
- `app/src/oss/` holds public stand-ins (Custom Layouts stubs, the prebuilt `libodpark.so`). Keep their
  signatures as they are; official builds replace them. Do not add features there.
- Do not commit keystores, `*.properties` with credentials, or agent context (`.agents/`, `.kiro/`, `.claude/`).
  CI runs `tools/ci/check-private-paths.sh` and a secret scan on every PR.

## Code rules
- Never name other third-party car apps in code, comments, strings or docs. Describe the behaviour instead.
- Do not create `.md` files (design notes, audits, reports, `docs/*.md`). Put the reasoning in the PR description
  and commit message, and keep code comments short. Edit an existing doc only when user-facing behaviour changes.
- New daemon paths go through `ScratchRoot`, not hard-coded `/data/local/tmp` paths.
- Translations: edit `values/strings.xml` and `web/i18n/en.json` only; other locales come from the translation sheets.
- Keep PRs small and single-purpose, and say in the PR how you verified the change (build, tests, device).

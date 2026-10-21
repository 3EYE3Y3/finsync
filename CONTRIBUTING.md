# Contributing

Thanks for helping. A few things keep FinSync dependable:

- **Open an issue first** for anything bigger than a small fix, so we can agree on the approach.
- **Safety first.** FinSync writes to people's music cards. Changes that delete, move or rename files need tests (the engine runs on Okio's `FakeFileSystem`, the app's storage code on a fake document tree), and must never touch files FinSync didn't write unless the user confirmed it.
- **Tests:** `./gradlew :engine:allTests :cli:test :app:testPlayDebugUnitTest :app:testGithubDebugUnitTest` must pass.
- **Style:** match the surrounding code; plain-English messages for users, no stack traces.
- **Commits:** small and focused, with a message that says why.
- **Licence:** by contributing you agree that your work is released under GPL-3.0-or-later.

Never include server addresses, tokens, keys or anything from your own library in issues, logs or test data.

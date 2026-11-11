# Contributing

Issues and pull requests are welcome. Code, comments, documentation and commit messages are written in English.

1. Open an issue first for anything larger than a small fix.
2. Run `./gradlew build test` before you push. It runs Android lint and ktlint (both must report nothing) and the unit tests, as CI does.
3. Keep a pull request focused on one change, with tests for the behavior it adds or fixes.
4. Document every public class or function you add. The library uses Kotlin explicit API mode.

The server decides who has access: the library runs the purchase flow, reports the result and never acknowledges a purchase. Changes to the bridge messages must follow the [bridge contract](https://github.com/pmakerhq/tillbell-rails/blob/main/docs/bridge-contract.md) of tillbell-rails.

To report a security problem, follow [SECURITY.md](SECURITY.md) instead of opening an issue.

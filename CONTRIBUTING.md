# Contributing to PhotoHost

Thanks for helping. Bug reports, testing on devices the author does not own, and ideas are all
welcome as [issues](../../issues). Security problems go through [`SECURITY.md`](SECURITY.md), not
public issues.

## Before writing code

Please open an issue first for anything larger than a small fix, so the approach can be agreed
before you spend time on it.

**Contributor licence.** PhotoHost is licensed under the AGPL-3.0, and its author may also want to
offer it under other terms in future. Contributions of code will therefore need a Contributor
Licence Agreement. One is not set up yet, so code pull requests cannot be merged for now; issues,
reports and testing are very welcome in the meantime.

## Building and testing

See the root [`README.md`](README.md#building-from-source). In short, with a JDK 17 or newer:

```
android/gradlew -p android assembleDebug
desktop/gradlew -p desktop test
```

The desktop tests start a real server and drive it with the phone's own client stack, over the
pinned TLS connection. Run them after any change to server code.

## Things to know about the code

- **The server exists twice.** The phone app (`android/.../server`) and the Windows app
  (`desktop/.../server`) each carry a copy of the library server. A fix to one almost always
  belongs in both. Places where the desktop copy deliberately differs are marked
  `DIVERGES FROM the phone app` in its source.
- **The web page exists once,** in `web/`, and both builds include it.
- **Both servers must create the same database.** A desktop test compares the latest Room schema
  exported by each app, and any schema change needs a migration in both.
- **Names stored on disk or sent on the wire keep their old `gpic` prefix** (`gpic.db`, the `gpic`
  cookie, `.gpic-library-id`). Renaming them would break existing libraries and pairings.

## Style

- Match the code around your change. Comments explain *why*, not what.
- Commit messages explain why the change was needed; the diff already shows what changed.
- Anything that can delete or overwrite a user's photos needs a test, and must err towards keeping
  them.

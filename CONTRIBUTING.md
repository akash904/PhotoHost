# Contributing to PhotoHost

Thanks for helping. Bug reports, testing on devices the author does not own, and ideas are all
welcome as [issues](../../issues). Security problems go through [`SECURITY.md`](SECURITY.md), not
public issues.

## Before writing code

Please open an issue first for anything larger than a small fix, so the approach can be agreed
before you spend time on it.

## Contributor terms

Pull requests are welcome, and there is nothing separate to sign. By submitting a contribution you
agree that:

1. **You have the right to submit it:** it is your own work, or you have permission to contribute
   it. This is the [Developer Certificate of Origin](https://developercertificate.org/).
2. **It is licensed under the AGPL-3.0,** like the rest of PhotoHost.
3. **The project's author may also distribute it under other licence terms,** for example if
   PhotoHost is ever offered under an additional licence. You keep the copyright in your work, and
   your contribution stays available under the AGPL-3.0 regardless.

Confirm this by signing off each commit with `git commit -s`, which adds a `Signed-off-by:` line
with your name and email.

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
- **Names stored on disk or sent on the wire are permanent** (`photohost.db`, the `photohost`
  cookie, `.photohost-library-id`, the settings file). Renaming one breaks every existing library
  or pairing, so it needs a migration, not just a new name.

## Style

- Match the code around your change. Comments explain *why*, not what.
- Commit messages explain why the change was needed; the diff already shows what changed.
- Anything that can delete or overwrite a user's photos needs a test, and must err towards keeping
  them.

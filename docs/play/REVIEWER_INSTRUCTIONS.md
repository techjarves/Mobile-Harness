# Play reviewer instructions

## Device requirements

- ARM64 Android device
- Android 9 or newer
- At least 4 GB RAM and approximately 2 GB free storage
- Stable internet connection for first setup

## First launch

1. Open Mobile Harness.
2. Review the compatibility screen.
3. Notification permission is requested only after tapping the notification permission button. It may be denied; foreground progress remains visible inside the app.
4. Choose core tools and tap Install. First setup normally takes 10–12 minutes.
5. The setup screen and ongoing notification show the same live installation session. Setup can be stopped and resumed safely.

## Provider access

The current release has no first-party Mobile Harness account. AI features require an Anthropic-compatible provider credential. Before review, place a time-limited, low-quota test credential and its exact base URL/model in Play Console **App access** instructions. Never include that credential in the APK, repository, screenshots, or this document.

## Suggested review flow

1. Create a Quick Project.
2. Send: `Create a small hello.html page with a heading.`
3. Review live tool activity and the final response.
4. Open Files and confirm `hello.html` exists.
5. Open Changes to review the file diff.
6. Open Terminal and run `ls -la`.
7. Start `python3 -m http.server 8000`, then open Preview and load `http://127.0.0.1:8000`.
8. Stop the server or active task using the visible Stop control.

## Important behavior

- Projects and conversations are stored locally in app-private storage.
- PRoot is not a hardened security boundary; only trusted code should be opened.
- The app downloads runtime components during user-started setup.
- Docker, systemd, nested containers, real root, and Android emulators are unsupported.

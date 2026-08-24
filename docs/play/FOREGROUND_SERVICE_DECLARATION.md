# Foreground-service declaration

Mobile Harness uses two user-initiated `specialUse` foreground services. The manifest subtype descriptions and Play Console declaration must match the behavior below.

## Runtime setup service

- **Trigger:** The user taps Install after reviewing setup and optional toolchains.
- **Purpose:** Download, verify, extract, repair, and configure the private Linux development runtime.
- **Why foreground execution is required:** Initial setup is lengthy and interruption can leave package configuration incomplete. The ongoing notification exposes progress and a Stop action.
- **User impact if deferred:** The coding workspace cannot run until setup finishes.
- **Stop behavior:** The notification and setup UI offer cancellation. Wake locks are released on completion, failure, or cancellation.

Suggested Play Console wording:

> User-started installation and repair of the app's private local development runtime. The operation can take several minutes, displays continuous progress in an ongoing notification, and can be stopped by the user.

## Coding task service

- **Trigger:** The user sends a coding task from an open project.
- **Purpose:** Keep the explicitly started local agent task and child command active while the user switches apps or locks the screen.
- **Why foreground execution is required:** File edits and test commands can take longer than a normal background-execution window. The notification shows current progress and a Stop action.
- **User impact if deferred:** The task is interrupted and partial changes require inspection or rollback.
- **Stop behavior:** The app and ongoing notification stop the active process and preserve recoverable task state.

Suggested Play Console wording:

> User-started local coding task that may read or change the active project and run commands. The ongoing notification shows task progress and provides an immediate Stop action.

## Demonstration video shot list

Record one continuous, reviewer-accessible video:

1. Open Mobile Harness and start runtime setup from the explicit Install button.
2. Show the ongoing notification, real progress, Open action, and Stop setup action.
3. Return to the app and show the same live setup session.
4. In a prepared project, send a small coding task.
5. Background the app and show the ongoing task notification and Stop action.
6. Return to the app, stop or complete the task, and show that the notification disappears.

Do not show real API keys, private source code, email notifications, or personal device information in the recording.

# Mobile Harness Privacy Policy

**Effective date:** August 24, 2026

Mobile Harness is a local-first Android development workspace. This policy explains what information the app handles, where it is stored, and when information leaves the device.

## Information handled by the app

Mobile Harness may handle the following information when you choose to provide it:

- AI-provider API keys and connection settings
- Prompts, AI responses, and conversation history
- Project files, imported documents, images, audio, and other attachments
- Terminal commands, command output, file changes, checkpoints, and diagnostic logs
- Basic device compatibility information such as Android version, processor architecture, memory, and available storage

## Local storage

Projects, conversations, attachments, runtime files, terminal history, and diagnostics are stored in the app's private storage on your device. Provider API keys are encrypted using a key managed by Android Keystore before they are persisted.

Mobile Harness does not currently include advertising or analytics SDKs and does not operate a first-party user account system.

## Information sent off the device

When you use an AI feature, the content required to answer your request may be transmitted to the provider you configured. Depending on the task, that content can include prompts, conversation context, relevant source code, file paths, file contents, tool results, and attachments. The provider processes that information under its own privacy policy and terms.

During runtime setup and maintenance, the app connects to software distribution services—including Ubuntu, Anthropic, Node.js, Composer, and configured Linux package repositories—to download required components. Those services may receive standard network information such as your IP address, request time, requested file, and user-agent information.

Mobile Harness does not sell personal information. It does not send projects or conversations to the Mobile Harness developer unless you explicitly share them, submit diagnostics, or use a future opt-in backup or support feature.

## Permissions

- **Internet and network state:** Download runtime components and communicate with your selected AI provider.
- **Notifications:** Report user-started setup and coding tasks running in the foreground or background.
- **Foreground service and wake lock:** Keep a user-started task active long enough to complete and prevent interruption while work is actively running.
- **Document picker access:** Import only files and folders you explicitly select through Android's system picker.

Notification permission and battery-optimization exemption are requested in context and can be declined. Some background features may be less reliable without them.

## Retention and deletion

Local information remains on the device until you delete the relevant project, clear it from the app, or uninstall Mobile Harness. Uninstalling the app removes its private local data according to Android's behavior.

Information already transmitted to an AI provider is governed by that provider's retention and deletion policies. Contact the provider directly for requests concerning information held by that provider.

## Security

Mobile Harness uses app-private Android storage, Android Keystore-backed credential encryption, HTTPS for external services, and checksum verification for supported runtime downloads. No system can guarantee complete security. Code executed inside the local Linux environment should be limited to projects and dependencies you trust.

## Children

Mobile Harness is a developer tool and is not directed to children under 13. The app does not knowingly collect personal information from children.

## Changes to this policy

This policy may be updated as Mobile Harness changes. Material changes will be reflected by updating the effective date and publishing the revised policy at the same public URL.

## Contact

For privacy questions or requests, open an issue at [github.com/techjarves/Mobile-Harness/issues](https://github.com/techjarves/Mobile-Harness/issues).

# Google Play Data safety worksheet

Use this worksheet when completing the Play Console Data safety form. Recheck it against the exact release before submitting.

## High-level answers

- The app does not contain ads or advertising SDKs.
- The app does not include analytics or crash-reporting SDKs in the current release.
- Data is encrypted in transit when communicating with external services.
- Users can delete local projects and chats in the app; uninstalling removes app-private data.
- Mobile Harness does not operate a first-party account system in the current release.

## Data sent off device

When the user configures and uses an AI provider, the following may be sent to that provider for **App functionality**:

| Play category | Examples | Collected/shared | Required | Handling |
| --- | --- | --- | --- | --- |
| User-generated content | Prompts, chat messages, selected project content, tool results | Collected by the selected provider | Required to use AI chat | Encrypted in transit; provider retention rules apply |
| Files and documents | Files deliberately attached or included in an agent request | Collected by the selected provider | Optional | Encrypted in transit; provider retention rules apply |
| Audio files | Audio deliberately attached by the user | Collected by the selected provider only when included in a request | Optional | V1 does not automatically transcribe audio |
| Authentication information | Provider API key or short-lived provider credential | Shared with the selected provider/gateway for authentication | Required for provider access | Encrypted locally and in transit |

The app itself does not send projects or conversations to the Mobile Harness developer unless a user deliberately shares them through a separate support channel.

## Other network recipients

During setup and maintenance, Ubuntu, Anthropic, Node.js, Composer, and configured package repositories may receive ordinary request metadata such as IP address, requested resource, request time, and user agent. These transfers are needed for **App functionality** and software downloads.

## Local-only data

Projects, conversations, attachments, terminal history, checkpoints, preferences, runtime files, and diagnostics are stored in app-private device storage. API keys are encrypted using an Android Keystore-backed key. Data that never leaves the device is not declared as collected under the Play definition.

## Verification before submission

- Confirm no analytics, advertising, telemetry, or crash-reporting SDK was added.
- Confirm every configured AI gateway is covered by the public privacy policy.
- Confirm the release contains no test keys or default private endpoints.
- Update this worksheet if cloud backup, first-party accounts, subscriptions, or diagnostics upload are introduced.

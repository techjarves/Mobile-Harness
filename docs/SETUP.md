# Setup and onboarding guide

This guide covers the recommended flow for installing and configuring Mobile Harness.

## Prerequisites

- Android 9 or newer
- ARM64 device (`arm64-v8a`)
- 4 GB RAM minimum
- Sufficient storage for runtime and toolchain installation
- Stable internet connection for setup and model access

## Installation

1. Download the latest APK from the release page.
2. Install the application on your Android device.
3. Grant the permissions required by the on-device setup wizard.
4. Complete the system readiness check.
5. Choose the toolchains and runtime packages you need.
6. Configure your AI provider or agent.

## Recommended first project

Start with a simple app or script to confirm the environment works end-to-end.

Suggested first tasks:

- create a simple web page
- scaffold a small API
- clone a demo repo
- run a local build and preview it

## Provider configuration

Use a compatible AI provider such as Anthropic, OpenRouter, DeepSeek, or a custom API-compatible endpoint.

Store keys in system-protected Android Keystore-backed storage whenever possible.

## Troubleshooting

### Runtime not starting

- confirm ARM64 support
- verify enough free storage
- retry setup after restarting the app

### Build failures or missing commands

- check whether the required runtime pack is installed
- confirm the terminal shell is active
- verify network access for package downloads

### Agent issues

- confirm API key validity
- confirm endpoint compatibility
- review provider-specific authentication requirements

## Next steps

After setup, continue to the Architecture guide and Roadmap to understand how the project works and what improvements are planned.

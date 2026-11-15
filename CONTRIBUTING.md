# Contributing to Mobile Harness

Thanks for your interest in improving Mobile Harness.

We welcome bug reports, documentation fixes, product ideas, and code contributions.

## How to contribute

1. Fork the repository.
2. Create a feature branch.
3. Make your changes.
4. Run the relevant validation steps.
5. Open a PR with a clear description.

## Development setup

```bash
git clone https://github.com/techjarves/Mobile-Harness.git
cd Mobile-Harness
./gradlew assembleDebug
```

For device deployment:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Pull request expectations

- Prefer small, focused PRs
- Include a clear summary of the problem and fix
- Add or update docs when behavior changes
- Include screenshots or a short demo when UI changes are involved
- Keep commit history readable and intentional

## Code quality guidelines

- Prefer simple, readable Kotlin and Compose code
- Keep Android lifecycle and process handling explicit
- Avoid unnecessary dependencies
- Document security-sensitive changes carefully
- Consider performance and battery impact on mobile devices

## Issue reporting

When filing an issue, include:

- device model and Android version
- architecture (`arm64-v8a` only)
- reproduction steps
- expected behavior
- actual behavior
- logs, screenshots, or app state if relevant

## Community values

We aim to keep this project welcoming, constructive, and respectful. Please keep discussion focused on improving the project and helping users.

Thank you for contributing.

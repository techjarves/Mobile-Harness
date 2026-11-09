# Security Policy

## Reporting a vulnerability

If you believe you have found a security vulnerability in Mobile Harness, please report it privately and responsibly.

Please do not open a public issue for security-sensitive problems.

Use one of the following:

- open a private GitHub security advisory if the repository supports it
- contact the maintainer through the repository's verified GitHub account

Please include:

- a clear description of the issue
- affected component or workflow
- reproduction steps where possible
- potential impact
- any suggested fix or mitigation

## Response expectations

We aim to acknowledge valid reports promptly and work toward a patch or mitigation as quickly as possible.

## Security considerations

This project intentionally deals with:

- API keys and credentials
- local runtime execution on Android
- networked model provider access
- file system access and app-install workflows

Because of this, we strongly encourage responsible disclosure and careful review of code that touches authentication, storage, file handling, or privileged runtime behavior.

## Supported versions

This repository currently follows the latest main branch and active release process. Security fixes are prioritized on the most recent branch state.

Thank you for helping keep Mobile Harness safe for users.

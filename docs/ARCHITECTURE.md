# Architecture overview

Mobile Harness is designed as a mobile developer workspace that brings together a modern UI layer, a private Linux userspace, and AI-driven coding workflows.

## High-level design

The repository is organized around a Kotlin-based Android app frontend and a runtime layer that launches Linux tooling inside a user-space environment.

### Android host layer

- Jetpack Compose UI screens
- app-level state and settings
- credential and secret storage
- runtime orchestration and lifecycle management

### Runtime layer

- private Ubuntu userspace environment
- toolchain support for Git, Node.js, Python, and native builds
- execution bridge for commands and scripts
- isolated project and session management

### AI and provider layer

- direct provider connections
- agent execution flow
- model-specific settings and provider compatibility
- local project and code context passing to the agent

## Core principles

- keep the workflow mobile-first
- preserve local development ergonomics
- minimize cloud dependence when possible
- secure sensitive credentials with Android-backed storage
- support simple developer onboarding

## Constraints and trade-offs

- ARM64-only support
- not a full VM or hardened container runtime
- some advanced terminal apps may require compatibility adjustments
- heavy compute tasks can be limited by battery and process policies

## Why this architecture

The goal is to let developers work in a real Linux-like environment without carrying a laptop, while still maintaining useful security boundaries, secure secret storage, and practical Android integration.

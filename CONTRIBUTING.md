# Contributing to Gradum

Thank you for considering contributing to Gradum. This document outlines the guidelines and
process for contributions.

## Table of Contents

* [Code of Conduct](#code-of-conduct)

* [Organizational Use](#organizational-use)

* [How to Report Bugs](#how-to-report-bugs)

* [Feature Requests and Discussions](#feature-requests-and-discussions)

* [Setting Up the Development Environment](#setting-up-the-development-environment)

* [Coding Standards](#coding-standards)

* [Pull Request Process](#pull-request-process)

* [AI Assistance Policy](#ai-assistance-policy)

* [Governance (BDFL)](#governance-bdfl)

* [Code Review Guidelines](#code-review-guidelines)

* [License](#license)

## Code of Conduct

This project is committed to providing a welcoming and harassment-free experience for everyone.
We expect all contributors to be respectful and constructive in all interactions.

Please report unacceptable behavior via GitHub Issues or email.

## Organizational Use

If you represent an organization, company, government entity, or institution and intend to use
Gradum in an official capacity, please post a courtesy notice in
[GitHub Discussions](https://github.com/gwy15/Gradum/discussions) before doing so. This is not
a request for approval: it is a courtesy to the community and helps maintain transparency.

This project is an independent open-source work maintained in a personal capacity. It is not
affiliated with, endorsed by, or sponsored by any organization, corporation, government entity,
or institution. Any use, fork, derivative, or reference by such entities must not imply
endorsement, affiliation, or sponsorship by the project or its maintainers.

## How to Report Bugs

1. Search [existing issues](https://github.com/gwy15/Gradum/issues) to avoid duplicates.
2. Use the **Bug Report** template when creating a new issue.
3. Include the following information:

    * Plugin error stack trace (if applicable)

    * IDE version (e.g., IntelliJ IDEA 2026.2 Build #IU-262.xxx)

    * Server-side version (if applicable, leave blank if not running a custom server)

    * Steps to reproduce the issue

    * Expected vs actual behavior

    * Screenshots or logs if helpful

## Feature Requests and Discussions

Before opening a feature request, please start a [GitHub Discussion](https://github.com/gwy15/Gradum/discussions)
to gather feedback from the community. This helps refine the idea before a formal proposal.

For other questions or discussions, use GitHub Issues or email the maintainers.

## Setting Up the Development Environment

### Prerequisites

| Requirement   | Version                                  |
|---------------|------------------------------------------|
| JDK           | 25 (recommended) or 21+                  |
| IntelliJ IDEA | 2026.2 (IU-262.x) for plugin development |
| Gradle        | 8.x (via wrapper, `./gradlew`)           |

### Step 1: Clone the Repository

```bash
git clone https://github.com/gwy15/Gradum.git
cd Gradum
```

### Step 2: Configure API Keys (Optional)

If you want to test with cloud LLM providers, set your API key in
`~/.gradle/gradle.properties` (user-level, recommended):

```properties
gradum.openAiApiKey=sk-your-key-here
```

You can also use the environment variable `GRADUM_OPENAI_API_KEY`.

### Step 3: Build the Server

```bash
./gradlew :build -x test
```

The fat JAR will be at `build/libs/gradum@0.9.2.jar`.

### Step 4: Run Tests

```bash
# All tests
./gradlew :test

# Plugin tests
./gradlew :plugin:test

# Run a specific test class
./gradlew :test --tests "gradum.skill.GlobSkillTest"
```

### Step 5: Set Up the Plugin for Development

The plugin requires vendored JARs from IntelliJ IDEA 2026.2. See the
[plugin README](plugin/README.md#special-build-setup) for detailed instructions.

### Step 6: Run Code Quality Checks

```bash
./gradlew :detekt
./gradlew :plugin:detekt
```

## Coding Standards

All Kotlin code must follow the conventions in:

* [CODING\_STANDARDS\_KOTLIN.md](docs/CODING_STANDARDS_KOTLIN.md): kotlin conventions, file headers,
  naming, and structure.

Key points:

* No single-letter variable names (except loop indices `i`, `j`, `k`).

* No Chinese variable names.

* All comments must be in English.

* No emoji in source files, comments, commit messages, or documentation.

* Use conventional commits for commit messages (e.g., `feat:`, `fix:`, `refactor:`, `chore:`).

## Pull Request Process

1. **Open a Discussion first** for any non-trivial change (more than \~100 lines). This avoids
   wasted effort on rejected PRs.
2. **Create a feature branch** from `main` (or the latest release branch).
3. **Follow the PR template** when submitting.
4. **Ensure CI passes**: all tests must pass and code quality checks must be clean.
5. **Update documentation** if your change affects public APIs, configuration, or build process.
6. **Request review** from the maintainers.

### What Gets Merged

| Change Type                                             | Required Approval       |
|---------------------------------------------------------|-------------------------|
| Bug fixes, tests, documentation improvements            | 1 maintainer            |
| New features, architectural changes, public API changes | Project lead (`@gwy15`) |

PRs that change more than 100 lines without a prior Discussion, or that add new framework
dependencies, will be closed without detailed review.

## AI Assistance Policy

Contributions that use AI-assisted coding tools (GitHub Copilot, Cursor, ChatGPT, etc.) must:

1. **Declare it in the PR description** state which tool (s) were used and how.
2. **Confirm manual review of every line** the contributor is responsible for all code,
   regardless of how it was generated.

AI-generated security reports, threat models, or vulnerability analyses are not accepted
as PRs. Real security findings must be reported via GitHub Security Advisories.

## Governance (BDFL)

This project operates under a **Benevolent Dictator for Life (BDFL)** model. The project lead (`@gwy15`) has final
authority over all decisions, including but not limited to:

* Architectural decisions (module boundaries, public APIs, dependency selection)

* Feature acceptance and prioritization

* Project direction and roadmap

While community input is welcome and encouraged through Discussions and Issues, the project lead
reserves the right to make final decisions. This model ensures the project maintains a consistent
vision and avoids stagnation from unresolved disagreements.

## Code Review Guidelines

* Reviewers focus on correctness, maintainability, and adherence to project conventions.

* Be respectful and constructive in review comments.

* Address review feedback promptly. If a PR goes stale for 30+ days, it may be closed.

## License

By contributing, you agree that your contributions will be licensed under the
[MIT License](LICENSE) that covers this project.

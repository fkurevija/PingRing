---
name: development-terminal
description: Handle development tasks requiring terminal commands
---

When working on development tasks that require system-level operations, use the `run_terminal_command` tool automatically when:

1. Setting up or checking the development environment
2. Running builds, tests, or package installations
3. Checking system status or configuration
4. Managing dependencies or tools
5. Performing file operations or system queries
6. Debugging connection issues or network problems

This is particularly useful for:
- Checking if required tools are installed (`java -version`, `node --version`)
- Running build commands (`./gradlew build`, `npm install`)
- Checking Android development tools (`adb devices`, `gradle --version`)
- Verifying file permissions or system information
- Performing debugging operations that require shell access

The agent should be cautious about which commands it executes and only run commands that are:
- Safe for the development environment
- Appropriate for the task at hand
- Likely to provide useful information for resolving the issue
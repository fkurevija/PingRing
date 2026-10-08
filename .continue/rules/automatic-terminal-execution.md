---
globs: ['**/*']
description: Automatic terminal command execution for development tasks
---

This rule enables automatic activation of terminal command execution in Continue when working on development tasks that require system commands. The agent should automatically use `run_terminal_command` when:

1. Setting up or checking the development environment
2. Running builds or tests
3. Checking system status or configuration
4. Managing dependencies or packages
5. Performing file operations or system queries
6. Debugging issues that require shell access

Specific scenarios where terminal commands should be automatically executed:
- When checking if tools are installed (e.g., `which java`, `node --version`)
- When running build commands (`gradle build`, `npm install`)
- When checking file permissions or system information
- When debugging connection issues or network problems
- When managing Android development tools (adb, gradle, etc.)
- When performing operations that require shell access

The terminal command execution should be context-aware and only run when:
- The conversation involves development setup or troubleshooting
- There's a clear need to execute system commands for the task at hand
- The command is safe and appropriate for the development environment
---
globs: ['**/*']
description: Automatic tool activation for code exploration and project understanding
---

This rule enables automatic activation of key Continue tools when working with the codebase. The agent should automatically use these tools to:

1. Understand project structure using view_repo_map
2. Search codebase for relevant implementations using codebase search
3. Find specific files using file_glob_search
4. Read file contents using read_file and read_file_range
5. List directory contents using ls
6. Run terminal commands using run_terminal_command
7. Search file contents using grep_search

These tools should be automatically invoked when the conversation context suggests that understanding or exploring the codebase would be beneficial, particularly during:
- Code review discussions
- Bug investigation
- Feature implementation planning
- Technical documentation creation
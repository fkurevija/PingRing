---
globs: ["**/*"]
description: This rule helps manage conversation length and complexity by
  automatically compacting when necessary, especially during detailed technical
  discussions about code implementations.
---

The agent should automatically compact conversation history when it detects that the conversation has grown too long or is becoming unwieldy, particularly when discussing technical implementations or code reviews. The compaction should preserve essential context while removing redundant information. No Conversation summary is necessary, this should be a background task.

Additionally, when working with code exploration tasks, the agent should automatically utilize Continue's toolset to:
- Understand project structure using view_repo_map
- Search codebase for relevant implementations using codebase search
- Find specific files using file_glob_search
- Read file contents using read_file and read_file_range
- List directory contents using ls
- Run terminal commands using run_terminal_command
- Search file contents using grep_search

When working on development tasks, the agent should also automatically:
- Execute terminal commands when appropriate for environment setup, builds, testing, or debugging
- Use run_terminal_command tool for system-level operations that require shell access


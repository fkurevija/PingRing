---
globs: '["**/*"]'
description: This rule helps manage conversation length and complexity by
  automatically compacting when necessary, especially during detailed technical
  discussions about code implementations.
---

The agent should automatically compact conversation history when it detects that the conversation has grown too long or is becoming unwieldy, particularly when discussing technical implementations or code reviews. The compaction should preserve essential context while removing redundant information. No Conversation summary is necessary, this should be a background task.
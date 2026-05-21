# UoA-P4P-2026-123
2026 Part 4 Project #123 at The University of Auckland - AI-based virtual parliament

## People
Supervisor: **Joerg Wicker**

Team members: **Albert Sun**, **Kieran Joe**

## File Structure

```
├── src/
│   └── engine/
│       ├── Main.java                   # entry point
│       ├── ChatManager.java
│       ├── openAi/
│       │   └── OpenAIChatManager.java  # OpenAI API manager
│       └── io/
│           ├── EngineOutput.java
│           └── ConsoleEngineOutput.java
├── resources/
│   └── prompts/
│       ├── BasePrompt.txt
│       └── PoliticanPrompt.txt
├── keys/
│   └── openAi/
│       ├── OpenAI_Key_TEMPLATE.txt
│       └── OpenAI_Key.txt              # git ignored
├── README.md
└── .gitignore
```
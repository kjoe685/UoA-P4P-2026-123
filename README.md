# UoA-P4P-2026-123
2026 Part 4 Project #123 at The University of Auckland - AI-based virtual parliament

## People
Supervisor: **Joerg Wicker**

Team members: **Albert Sun**, **Kieran Joe**

## API Keys
in the `keys` directory, there are subdirectories for each LLM provider.
These contain templates for API key documents.
Please create a copy and rename to remove the `_TEMPLATE` from the filename, placing the new file in the same directory as the template.
e.g.
```
keys/openAi/OpenAI_Key_TEMPLATE.txt  -> keys/openAi/OpenAI_Key.txt
```

## File Structure

```
├── src/
│   └── engine/
│       ├── Main.java                   # entry point
│       ├── ChatManager.java
│       ├── openAi/
│       │   └── OpenAIChatManager.java  # OpenAI API manager
│       ├── io/
│       │   ├── EngineOutput.java
│       │   └── ConsoleEngineOutput.java
│       ├── prompt/
│       │   └── PromptManager.java      # Prompt manager/assembler
│       └── utils/
│           └── FileTextReader.java     # Utilities for reading text files   
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
package engine;

import engine.agent.AdversarialStrategy;
import engine.agent.Agent;
import engine.agent.Party;
import engine.debate.DebateManager;
import engine.io.ConsoleEngineOutput;
import engine.io.EngineOutput;
import engine.openAi.OpenAIChatManager;
import engine.prompt.PromptManager;
import engine.utils.FileTextReader;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static final String API_KEY_PATH = "keys/openAi/OpenAI_Key.txt";
    private static final int DEFAULT_ROUNDS = 3;

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        Scanner scanner = new Scanner(System.in);
        FileTextReader fileTextReader = new FileTextReader();

        String apiKey;
        try {
            apiKey = fileTextReader.readText(API_KEY_PATH).trim();
        } catch (RuntimeException e) {
            System.out.println("Could not read an OpenAI API key from " + API_KEY_PATH);
            System.out.println("Copy keys/openAi/OpenAI_Key_TEMPLATE.txt to keys/openAi/OpenAI_Key.txt "
                    + "and paste your key inside, then try again.");
            return;
        }

        if (apiKey.isBlank() || apiKey.contains("#")) {
            System.out.println(API_KEY_PATH + " does not contain a real API key yet.");
            System.out.println("Replace the placeholder in that file with your actual OpenAI API key, then try again.");
            return;
        }

        System.out.println("=== AI-Based Virtual Parliament ===");
        System.out.println();

        System.out.print("Enter the debate topic(s), separated by '|' for an agenda of multiple (e.g. "
                + "Topic A | Topic B): ");
        List<String> topics = parseTopics(scanner.nextLine().trim());

        System.out.println();
        System.out.println("Available parties:");
        Party[] parties = Party.values();
        for (int i = 0; i < parties.length; i++) {
            System.out.println("  " + (i + 1) + ". " + parties[i].getDisplayName());
        }
        System.out.println();
        System.out.print("Enter the party numbers to include, separated by commas (blank = all): ");
        List<Party> selectedParties = parseSelectedParties(scanner.nextLine().trim(), parties);

        System.out.println();
        System.out.print("Enter the number of debate rounds per topic (blank = " + DEFAULT_ROUNDS + "): ");
        int rounds = parseRounds(scanner.nextLine().trim());

        PromptManager promptManager = new PromptManager(fileTextReader);
        EngineOutput output = new ConsoleEngineOutput();
        List<Agent> agents = new ArrayList<>();

        for (Party party : selectedParties) {
            AdversarialStrategy strategy = askAdversarialStrategy(scanner, party);

            String agentName = party.getDisplayName() + " MP";
            String systemPrompt = promptManager.assemblePersonaPrompt(agentName, party, strategy, topics.get(0));
            OpenAIChatManager chatManager = new OpenAIChatManager(apiKey);
            agents.add(new Agent(agentName, party, strategy, chatManager, systemPrompt));
        }

        System.out.println();
        DebateManager debateManager = new DebateManager(agents, topics, rounds, output);
        try {
            debateManager.run();
        } catch (RuntimeException e) {
            System.out.println("The debate stopped because of an API error: " + e.getMessage());
            return;
        }

        System.out.println("Debate complete.");
    }

    private static List<String> parseTopics(String input) {
        List<String> topics = new ArrayList<>();
        if (!input.isBlank()) {
            for (String token : input.split("\\|")) {
                String trimmed = token.trim();
                if (!trimmed.isBlank()) {
                    topics.add(trimmed);
                }
            }
        }
        if (topics.isEmpty()) {
            topics.add("Whether the retirement age should be raised");
        }
        return topics;
    }

    private static List<Party> parseSelectedParties(String input, Party[] parties) {
        List<Party> selected = new ArrayList<>();
        if (!input.isBlank()) {
            for (String token : input.split(",")) {
                try {
                    int index = Integer.parseInt(token.trim()) - 1;
                    if (index >= 0 && index < parties.length) {
                        selected.add(parties[index]);
                    }
                } catch (NumberFormatException ignored) {
                    // skip invalid tokens
                }
            }
        }
        if (selected.isEmpty()) {
            for (Party party : parties) {
                selected.add(party);
            }
        }
        return selected;
    }

    private static int parseRounds(String input) {
        if (!input.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(input));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_ROUNDS;
    }

    private static AdversarialStrategy askAdversarialStrategy(Scanner scanner, Party party) {
        System.out.println();
        System.out.print("Should the " + party.getDisplayName() + " agent be adversarial? (y/N): ");
        String answer = scanner.nextLine().trim();
        if (!answer.equalsIgnoreCase("y")) {
            return AdversarialStrategy.NONE;
        }

        AdversarialStrategy[] strategies = AdversarialStrategy.values();
        System.out.println("  Choose an adversarial strategy:");
        for (int i = 1; i < strategies.length; i++) {
            System.out.println("    " + i + ". " + strategies[i].name());
        }
        System.out.print("  Enter choice (blank = " + strategies[1].name() + "): ");
        String choice = scanner.nextLine().trim();
        if (!choice.isBlank()) {
            try {
                int index = Integer.parseInt(choice);
                if (index >= 1 && index < strategies.length) {
                    return strategies[index];
                }
            } catch (NumberFormatException ignored) {
                // fall through to default adversarial strategy
            }
        }
        return strategies[1];
    }
}

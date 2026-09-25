package engine;

import engine.agent.AdversarialStrategy;
import engine.agent.Agent;
import engine.agent.Party;
import engine.config.ConfigurationSnapshot;
import engine.config.EngineConfig;
import engine.debate.DebateManager;
import engine.io.ConsoleEngineOutput;
import engine.provider.ProviderFactory;
import engine.config.ModelConfig;
import engine.evaluation.llm.LlmEvaluationResources;
import engine.prompt.PromptManager;
import engine.transcript.Participant;
import engine.utils.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;

public final class Main {
    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        int status = execute(args);
        if (status != 0) System.exit(status);
    }

    static int execute(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("evaluate")) {
                return engine.evaluation.EvaluationCommand.execute(java.util.Arrays.copyOfRange(args, 1, args.length), System.out);
            }
            run(args);
            return 0;
        } catch (NoSuchElementException e) {
            System.err.println("Input ended; no further debate turns will be run.");
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private static void run(String[] args) {
        Path resources = Path.of("resources");
        Path transcriptPath = null;
        boolean validateOnly = false;
        String agentPreset = null;
        java.util.Map<Party, String> partyPresets = new java.util.EnumMap<>(Party.class);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--resources", "--transcript", "--agent-model", "--party-model" -> {
                    String option = args[i];
                    if (++i == args.length) throw new IllegalArgumentException("Missing value for " + option);
                    if (option.equals("--resources")) resources = Path.of(args[i]);
                    else if (option.equals("--transcript")) transcriptPath = Path.of(args[i]);
                    else if (option.equals("--agent-model")) agentPreset = args[i];
                    else {
                        String[] assignment = args[i].split("=", -1);
                        if (assignment.length != 2 || assignment[1].isBlank()) throw new IllegalArgumentException("Use --party-model PARTY=PRESET");
                        Party party;
                        try { party = Party.valueOf(assignment[0]); }
                        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown party in --party-model"); }
                        if (partyPresets.putIfAbsent(party, assignment[1]) != null) throw new IllegalArgumentException("Duplicate party model override");
                    }
                }
                case "--validate-config" -> validateOnly = true;
                case "--help" -> {
                    System.out.println("Usage: java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar"
                            + " [--resources DIR] [--validate-config] [--transcript FILE]"
                            + " [--agent-model PRESET] [--party-model PARTY=PRESET]");
                    System.out.println("       java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate --help");
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown command-line option");
            }
        }

        // Validate and freeze every active template/profile before reading credentials or making paid calls.
        ConfigurationSnapshot snapshot = ConfigurationSnapshot.load(resources);
        EngineConfig config = snapshot.config();
        String defaultPreset = agentPreset == null ? config.agentModelPreset() : agentPreset;
        if (!config.models().containsKey(defaultPreset) || !config.models().keySet().containsAll(partyPresets.values()))
            throw new IllegalArgumentException("Unknown agent model preset");
        if (validateOnly) {
            LlmEvaluationResources.load(resources);
            System.out.println("Configuration, templates, evaluator rubric, and Hansard sample are valid. No API calls made.");
            return;
        }

        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        System.out.println("=== AI-Based Virtual Parliament ===");
        System.out.print("Enter debate topics separated by '|' (blank = configured default): ");
        List<String> topics = parseTopics(scanner.nextLine().trim(), config.defaultTopic());
        Party[] parties = Party.values();
        System.out.println("Available parties:");
        for (int i = 0; i < parties.length; i++) {
            System.out.println("  " + (i + 1) + ". " + config.parties().get(parties[i]).displayName());
        }
        System.out.print("Enter party numbers separated by commas (blank = all): ");
        List<Party> selected = parseSelectedParties(scanner.nextLine().trim(), parties);
        System.out.print("Enter rounds per topic (blank = " + config.defaultRounds() + "): ");
        int rounds = parseRounds(scanner.nextLine().trim(), config.defaultRounds());

        PromptManager prompts = new PromptManager(snapshot);
        ProviderFactory providers = new ProviderFactory();
        // Resolve all selected credentials before any turn can be generated.
        java.util.Map<Party, ModelConfig> models = new java.util.EnumMap<>(Party.class);
        for (Party party : selected) {
            ModelConfig model = config.models().get(partyPresets.getOrDefault(party, defaultPreset));
            models.put(party, model);
            providers.forModel(model);
        }
        List<Agent> agents = new ArrayList<>();
        for (Party party : selected) {
            var profile = config.parties().get(party);
            AdversarialStrategy strategy = askAdversarialStrategy(scanner, profile.displayName());
            String name = profile.displayName() + " MP";
            List<String> excerpts = snapshot.excerpts().getExcerpts(party);
            String systemPrompt = prompts.assemblePersonaPrompt(name, profile, strategy, excerpts);
            agents.add(new Agent(new Participant(party.name(), name, profile.displayName()),
                    strategy, providers.forModel(models.get(party)), systemPrompt, excerpts, models.get(party)));
        }

        DebateManager debate = new DebateManager(agents, topics, rounds, new ConsoleEngineOutput(),
                prompts, config.interruptions());
        try {
            debate.run();
            System.out.println("Debate complete.");
        } finally {
            if (transcriptPath != null) {
                try {
                    Files.writeString(transcriptPath, Json.write(debate.transcript()), StandardCharsets.UTF_8);
                    System.out.println("Public transcript saved to " + transcriptPath);
                } catch (IOException e) {
                    System.err.println("Could not write the public transcript.");
                }
            }
        }
    }

    private static List<String> parseTopics(String input, String defaultTopic) {
        List<String> topics = new ArrayList<>();
        for (String token : input.split("\\|")) {
            if (!token.isBlank()) topics.add(token.trim());
        }
        return topics.isEmpty() ? List.of(defaultTopic) : topics;
    }

    private static List<Party> parseSelectedParties(String input, Party[] parties) {
        List<Party> selected = new ArrayList<>();
        for (String token : input.split(",")) {
            try {
                int index = Integer.parseInt(token.trim()) - 1;
                if (index >= 0 && index < parties.length && !selected.contains(parties[index])) selected.add(parties[index]);
            } catch (NumberFormatException ignored) {
                // Preserve the prototype's forgiving interactive input handling.
            }
        }
        return selected.isEmpty() ? List.of(parties) : selected;
    }

    private static int parseRounds(String input, int defaultRounds) {
        try {
            return Math.max(1, Integer.parseInt(input));
        } catch (NumberFormatException ignored) {
            return defaultRounds;
        }
    }

    private static AdversarialStrategy askAdversarialStrategy(Scanner scanner, String partyName) {
        System.out.print("Should the " + partyName + " agent be adversarial? (y/N): ");
        if (!scanner.nextLine().trim().equalsIgnoreCase("y")) return AdversarialStrategy.NONE;
        AdversarialStrategy[] strategies = AdversarialStrategy.values();
        for (int i = 1; i < strategies.length; i++) System.out.println("  " + i + ". " + strategies[i].name());
        System.out.print("Enter strategy (blank = " + strategies[1].name() + "): ");
        try {
            int index = Integer.parseInt(scanner.nextLine().trim());
            if (index >= 1 && index < strategies.length) return strategies[index];
        } catch (NumberFormatException ignored) {
            // Fall back to the first adversarial strategy.
        }
        return strategies[1];
    }
}

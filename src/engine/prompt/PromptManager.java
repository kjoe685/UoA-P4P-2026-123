package engine.prompt;

import engine.agent.AdversarialStrategy;
import engine.agent.HansardExcerpts;
import engine.agent.Party;
import engine.utils.FileTextReader;

import java.util.List;

public class PromptManager {

    private static final String BASE_PROMPT_PATH = "resources/prompts/BasePrompt.txt";
    private static final String POLITICIAN_PROMPT_PATH = "resources/prompts/PoliticanPrompt.txt";

    private final FileTextReader fileTextReader;
    private final HansardExcerpts hansardExcerpts;

    public PromptManager(FileTextReader fileTextReader) {
        this.fileTextReader = fileTextReader;
        this.hansardExcerpts = new HansardExcerpts(fileTextReader);
    }

    public String assemblePersonaPrompt(String agentName, Party party, AdversarialStrategy strategy, String topic) {
        String basePrompt = fileTextReader.readText(BASE_PROMPT_PATH);
        String personaPrompt = fileTextReader.readText(POLITICIAN_PROMPT_PATH)
                .replace("{{AGENT_NAME}}", agentName)
                .replace("{{PARTY_NAME}}", party.getDisplayName())
                .replace("{{IDEOLOGY}}", party.getIdeology())
                .replace("{{TOPIC}}", topic);

        StringBuilder prompt = new StringBuilder();
        prompt.append(basePrompt).append("\n\n").append(personaPrompt);

        List<String> excerpts = hansardExcerpts.getExcerpts(party);
        if (!excerpts.isEmpty()) {
            prompt.append("\n\nHere are real excerpts of ").append(party.getDisplayName())
                    .append(" MPs speaking in the New Zealand House of Representatives (from official Hansard "
                            + "transcripts), to ground your rhetorical style. Use them only to inform tone, phrasing, "
                            + "and parliamentary convention. Do not mention, name, or impersonate any specific real "
                            + "individual:");
            for (String excerpt : excerpts) {
                prompt.append("\n- \"").append(excerpt).append("\"");
            }
        }

        if (strategy != AdversarialStrategy.NONE) {
            prompt.append("\n\nAdversarial directive: ").append(strategy.getInstruction());
        }

        return prompt.toString();
    }
}

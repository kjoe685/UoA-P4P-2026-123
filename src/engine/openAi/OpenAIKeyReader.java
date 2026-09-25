package engine.openAi;

import engine.utils.FileTextReader;

public final class OpenAIKeyReader {

    public static final String API_KEY_PATH = "keys/openAi/OpenAI_Key.txt";

    private OpenAIKeyReader() {}

    /**
     * Reads the OpenAI API key from {@link #API_KEY_PATH}.
     *
     * @throws IllegalStateException with a user-facing explanation if the key file is missing or still holds
     *                               the template placeholder
     */
    public static String read(FileTextReader fileTextReader) {
        String apiKey;
        try {
            apiKey = fileTextReader.readText(API_KEY_PATH).trim();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not read an OpenAI API key from " + API_KEY_PATH + ". "
                    + "Copy keys/openAi/OpenAI_Key_TEMPLATE.txt to keys/openAi/OpenAI_Key.txt "
                    + "and paste your key inside, then try again.");
        }

        if (apiKey.isBlank() || apiKey.contains("#")) {
            throw new IllegalStateException(API_KEY_PATH + " does not contain a real API key yet. "
                    + "Replace the placeholder in that file with your actual OpenAI API key, then try again.");
        }
        return apiKey;
    }
}

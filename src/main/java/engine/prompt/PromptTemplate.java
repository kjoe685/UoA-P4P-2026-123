package engine.prompt;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the template itself, then substitutes once (inserted text is never reinterpreted). */
public final class PromptTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z][A-Z0-9_]*)}}");
    private final String name;
    private final String text;
    private final Set<String> placeholders;

    public PromptTemplate(String name, String text, Set<String> expected) {
        this.name = name;
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Empty template: " + name);
        this.text = text;
        Set<String> found = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
        String remainder = PLACEHOLDER.matcher(text).replaceAll("");
        if (remainder.contains("{{") || remainder.contains("}}") || !found.equals(expected)) {
            throw new IllegalArgumentException("Invalid placeholders in " + name + "; expected " + expected);
        }
        this.placeholders = Set.copyOf(found);
    }

    public String render(Map<String, String> values) {
        if (!values.keySet().equals(placeholders) || values.values().stream().anyMatch(v -> v == null)) {
            throw new IllegalArgumentException("Missing or unexpected substitutions for " + name);
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(values.get(matcher.group(1))));
        matcher.appendTail(result);
        return result.toString();
    }

    @Override public String toString() { return "PromptTemplate[" + name + "]"; }
}

package engine.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import engine.ChatManager;
import engine.agent.AdversarialStrategy;
import engine.agent.Agent;
import engine.agent.Party;
import engine.openAi.OpenAIKeyReader;
import engine.prompt.PromptManager;
import engine.utils.FileTextReader;
import engine.utils.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * JSON API used by the web frontend.
 *
 * <pre>
 * GET  /api/config                  parties, adversarial strategies, defaults, and whether an API key is set up
 * POST /api/debates                 start a sitting: {"topics":[...], "rounds":3, "members":[{"party","strategy"}]}
 * GET  /api/debates/{id}/events     Server-Sent Events stream of the sitting (replays from the start)
 * POST /api/debates/{id}/speaker    a ruling from the chair: {"message":"Order!"}
 * POST /api/debates/{id}/adjourn    stop the sitting
 * </pre>
 */
public class ApiHandler implements HttpHandler {

    private static final String DEFAULT_TOPIC = "Whether the retirement age should be raised";
    private static final int DEFAULT_ROUNDS = 3;
    private static final int MAX_ROUNDS = 10;
    private static final int MAX_TOPICS = 10;
    private static final int MAX_TOPIC_LENGTH = 300;
    private static final int MAX_RULING_LENGTH = 500;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final long KEEP_ALIVE_MILLIS = 15_000;

    private final FileTextReader fileTextReader;
    private final Function<String, ChatManager> chatManagerFactory;
    private final Map<String, DebateSession> sessions = new ConcurrentHashMap<>();

    /**
     * @param chatManagerFactory creates a fresh {@link ChatManager} for each MP agent, given the API key
     */
    public ApiHandler(FileTextReader fileTextReader, Function<String, ChatManager> chatManagerFactory) {
        this.fileTextReader = fileTextReader;
        this.chatManagerFactory = chatManagerFactory;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (BadRequestException e) {
            sendJson(exchange, e.status, "{\"error\":" + DebateSession.quote(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendJson(exchange, 500, "{\"error\":" + DebateSession.quote("Internal server error: " + e) + "}");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] parts = exchange.getRequestURI().getPath().replaceAll("/+$", "").split("/");
        // parts[0] is "" and parts[1] is "api"

        if (parts.length == 3 && parts[2].equals("config")) {
            requireMethod(method, "GET");
            sendJson(exchange, 200, configJson());
            return;
        }
        if (parts.length == 3 && parts[2].equals("debates")) {
            requireMethod(method, "POST");
            DebateSession session = startDebate(readJsonBody(exchange));
            sendJson(exchange, 201, "{\"id\":" + DebateSession.quote(session.getId()) + "}");
            return;
        }
        if (parts.length == 5 && parts[2].equals("debates")) {
            DebateSession session = sessions.get(parts[3]);
            if (session == null) {
                throw new BadRequestException(404, "No sitting with id " + parts[3]);
            }
            switch (parts[4]) {
                case "events":
                    requireMethod(method, "GET");
                    streamEvents(exchange, session);
                    return;
                case "speaker":
                    requireMethod(method, "POST");
                    addRuling(session, readJsonBody(exchange));
                    sendJson(exchange, 202, "{}");
                    return;
                case "adjourn":
                    requireMethod(method, "POST");
                    session.adjourn();
                    sendJson(exchange, 202, "{}");
                    return;
                default:
                    break;
            }
        }
        throw new BadRequestException(404, "Unknown API endpoint");
    }

    private String configJson() {
        String apiKeyProblem = null;
        try {
            OpenAIKeyReader.read(fileTextReader);
        } catch (IllegalStateException e) {
            apiKeyProblem = e.getMessage();
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"apiKeyConfigured\":").append(apiKeyProblem == null)
                .append(",\"apiKeyProblem\":").append(DebateSession.quote(apiKeyProblem))
                .append(",\"defaultTopic\":").append(DebateSession.quote(DEFAULT_TOPIC))
                .append(",\"defaultRounds\":").append(DEFAULT_ROUNDS)
                .append(",\"maxRounds\":").append(MAX_ROUNDS)
                .append(",\"maxTopics\":").append(MAX_TOPICS)
                .append(",\"parties\":[");
        Party[] parties = Party.values();
        for (int i = 0; i < parties.length; i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"id\":").append(DebateSession.quote(parties[i].name()))
                    .append(",\"name\":").append(DebateSession.quote(parties[i].getDisplayName()))
                    .append(",\"ideology\":").append(DebateSession.quote(parties[i].getIdeology()))
                    .append("}");
        }
        json.append("],\"strategies\":[");
        AdversarialStrategy[] strategies = AdversarialStrategy.values();
        for (int i = 0; i < strategies.length; i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"id\":").append(DebateSession.quote(strategies[i].name()))
                    .append(",\"instruction\":").append(DebateSession.quote(strategies[i].getInstruction()))
                    .append("}");
        }
        return json.append("]}").toString();
    }

    private DebateSession startDebate(Map<String, Object> body) {
        List<String> topics = new ArrayList<>();
        for (Object item : listField(body, "topics")) {
            if (!(item instanceof String)) {
                throw new BadRequestException(400, "Each topic must be a string");
            }
            String topic = ((String) item).trim();
            if (topic.length() > MAX_TOPIC_LENGTH) {
                throw new BadRequestException(400, "Topics must be at most " + MAX_TOPIC_LENGTH + " characters");
            }
            if (!topic.isEmpty()) {
                topics.add(topic);
            }
        }
        if (topics.size() > MAX_TOPICS) {
            throw new BadRequestException(400, "The Order Paper can hold at most " + MAX_TOPICS + " topics");
        }
        if (topics.isEmpty()) {
            topics.add(DEFAULT_TOPIC);
        }

        Object roundsValue = body.get("rounds");
        int rounds = roundsValue instanceof Number ? ((Number) roundsValue).intValue() : DEFAULT_ROUNDS;
        if (rounds < 1 || rounds > MAX_ROUNDS) {
            throw new BadRequestException(400, "Rounds per topic must be between 1 and " + MAX_ROUNDS);
        }

        List<Party> parties = new ArrayList<>();
        List<AdversarialStrategy> strategies = new ArrayList<>();
        Set<Party> seen = EnumSet.noneOf(Party.class);
        for (Object item : listField(body, "members")) {
            if (!(item instanceof Map)) {
                throw new BadRequestException(400, "Each member must be an object");
            }
            Map<?, ?> member = (Map<?, ?>) item;
            Party party = enumValue(Party.class, member.get("party"), "party");
            Object strategyValue = member.get("strategy");
            AdversarialStrategy strategy = strategyValue == null
                    ? AdversarialStrategy.NONE
                    : enumValue(AdversarialStrategy.class, strategyValue, "strategy");
            if (!seen.add(party)) {
                throw new BadRequestException(400, party.getDisplayName() + " was selected more than once");
            }
            parties.add(party);
            strategies.add(strategy);
        }
        if (parties.isEmpty()) {
            throw new BadRequestException(400, "Select at least one party to take their seats");
        }

        String apiKey;
        try {
            apiKey = OpenAIKeyReader.read(fileTextReader);
        } catch (IllegalStateException e) {
            throw new BadRequestException(400, e.getMessage());
        }

        PromptManager promptManager = new PromptManager(fileTextReader);
        List<Agent> agents = new ArrayList<>();
        for (int i = 0; i < parties.size(); i++) {
            Party party = parties.get(i);
            String agentName = party.getDisplayName() + " MP";
            String systemPrompt = promptManager.assemblePersonaPrompt(agentName, party, strategies.get(i), topics.get(0));
            agents.add(new Agent(agentName, party, strategies.get(i), chatManagerFactory.apply(apiKey), systemPrompt));
        }

        DebateSession session = new DebateSession(UUID.randomUUID().toString(), agents, topics, rounds);
        sessions.put(session.getId(), session);
        session.start();
        return session;
    }

    private void addRuling(DebateSession session, Map<String, Object> body) {
        Object message = body.get("message");
        if (!(message instanceof String) || ((String) message).isBlank()) {
            throw new BadRequestException(400, "A ruling needs some text");
        }
        String ruling = ((String) message).trim();
        if (ruling.length() > MAX_RULING_LENGTH) {
            throw new BadRequestException(400, "Rulings must be at most " + MAX_RULING_LENGTH + " characters");
        }
        if (session.isFinished()) {
            throw new BadRequestException(409, "The House has already adjourned");
        }
        session.addSpeakerRuling(ruling);
    }

    private void streamEvents(HttpExchange exchange, DebateSession session) throws IOException {
        // EventSource sends the id of the last event it saw when it reconnects, so resume after it.
        int next = 0;
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventId != null) {
            try {
                next = Integer.parseInt(lastEventId.trim()) + 1;
            } catch (NumberFormatException ignored) {
                // replay from the start
            }
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();
        try {
            while (session.hasMoreEvents(next)) {
                List<String> batch = session.awaitEvents(next, KEEP_ALIVE_MILLIS);
                StringBuilder chunk = new StringBuilder();
                if (batch.isEmpty()) {
                    // A comment line; keeps proxies from timing out and detects closed tabs.
                    chunk.append(": keep-alive\n\n");
                }
                for (String event : batch) {
                    chunk.append("id: ").append(next++).append("\ndata: ").append(event).append("\n\n");
                }
                out.write(chunk.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The browser went away; nothing to clean up.
        }
    }

    private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        // Requiring a JSON content type means browsers must send a CORS preflight for cross-origin requests,
        // which this server never approves, so other websites cannot start debates on the user's API key.
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new BadRequestException(415, "Requests must be sent as application/json");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new BadRequestException(413, "Request body is too large");
        }
        Object parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new BadRequestException(400, "Request body is not valid JSON");
        }
        if (!(parsed instanceof Map)) {
            throw new BadRequestException(400, "Request body must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) parsed;
        return body;
    }

    private static List<?> listField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            throw new BadRequestException(400, "\"" + field + "\" must be a list");
        }
        return (List<?>) value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String field) {
        if (value instanceof String) {
            try {
                return Enum.valueOf(type, (String) value);
            } catch (IllegalArgumentException ignored) {
                // fall through to the error below
            }
        }
        throw new BadRequestException(400, "Unknown " + field + ": " + value);
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equalsIgnoreCase(expected)) {
            throw new BadRequestException(405, "Use " + expected + " for this endpoint");
        }
    }

    static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class BadRequestException extends RuntimeException {
        private final int status;

        private BadRequestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

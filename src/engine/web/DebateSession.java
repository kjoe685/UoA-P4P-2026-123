package engine.web;

import engine.agent.Agent;
import engine.debate.DebateManager;
import engine.io.EngineOutput;
import engine.utils.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * One sitting of the House started from the web frontend. Runs a {@link DebateManager} on a background thread and
 * records everything it outputs as an ordered log of JSON events, which {@link ApiHandler} streams to the browser.
 * Keeping the full log (rather than only pushing live) lets a browser that reconnects or reloads replay the sitting.
 */
public class DebateSession implements EngineOutput {

    private final String id;
    private final List<Agent> agents;
    private final List<String> topics;
    private final int roundsPerTopic;
    private final DebateManager debateManager;
    private final Thread thread;

    // Guarded by this.
    private final List<String> events = new ArrayList<>();
    private boolean finished = false;

    public DebateSession(String id, List<Agent> agents, List<String> topics, int roundsPerTopic) {
        this.id = id;
        this.agents = agents;
        this.topics = topics;
        this.roundsPerTopic = roundsPerTopic;
        this.debateManager = new DebateManager(agents, topics, roundsPerTopic, this);
        this.thread = new Thread(this::runDebate, "debate-" + id);
        this.thread.setDaemon(true);
    }

    public String getId() {
        return id;
    }

    public void start() {
        record(sittingEvent());
        thread.start();
    }

    /** Stops the debate as soon as possible, abandoning any speech that is still being generated. */
    public void adjourn() {
        debateManager.requestStop();
        thread.interrupt();
    }

    public void addSpeakerRuling(String ruling) {
        debateManager.addSpeakerRuling(ruling);
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    /** True while there are events at or after {@code fromIndex} that have not been produced or read yet. */
    public synchronized boolean hasMoreEvents(int fromIndex) {
        return !finished || fromIndex < events.size();
    }

    /**
     * Returns every event from {@code fromIndex} onwards, waiting up to {@code timeoutMillis} for one to arrive if
     * there are none yet. Returns an empty list on timeout.
     */
    public synchronized List<String> awaitEvents(int fromIndex, long timeoutMillis) throws InterruptedException {
        if (fromIndex >= events.size() && !finished) {
            wait(timeoutMillis);
        }
        if (fromIndex >= events.size()) {
            return List.of();
        }
        return new ArrayList<>(events.subList(fromIndex, events.size()));
    }

    @Override
    public void topicStarted(int topicNumber, int totalTopics, String topic) {
        record("{\"type\":\"topic\",\"number\":" + topicNumber + ",\"total\":" + totalTopics
                + ",\"topic\":" + quote(topic) + "}");
    }

    @Override
    public void displaySpeakerMessage(String message) {
        record("{\"type\":\"chair\",\"text\":" + quote(message) + "}");
    }

    @Override
    public void speakerCalled(Agent speaker, boolean interjection) {
        record("{\"type\":\"calling\"," + memberFields(speaker) + ",\"interjection\":" + interjection + "}");
    }

    @Override
    public void displayMessage(Agent speaker, String message, boolean interjection) {
        record("{\"type\":\"speech\"," + memberFields(speaker) + ",\"interjection\":" + interjection
                + ",\"text\":" + quote(message) + "}");
    }

    private void runDebate() {
        String outcome = "complete";
        try {
            debateManager.run();
            if (debateManager.isStopRequested()) {
                outcome = "adjourned";
            }
        } catch (RuntimeException e) {
            if (debateManager.isStopRequested()) {
                // Adjourning interrupts the in-flight API call, which surfaces here as an exception.
                outcome = "adjourned";
            } else {
                record("{\"type\":\"error\",\"message\":" + quote(describe(e)) + "}");
                outcome = "error";
            }
        }
        finish(outcome);
    }

    private synchronized void record(String eventJson) {
        events.add(eventJson);
        notifyAll();
    }

    private synchronized void finish(String outcome) {
        events.add("{\"type\":\"adjourned\",\"outcome\":" + quote(outcome)
                + ",\"at\":" + System.currentTimeMillis() + "}");
        finished = true;
        notifyAll();
    }

    private String sittingEvent() {
        StringBuilder json = new StringBuilder("{\"type\":\"sitting\",\"id\":").append(quote(id))
                .append(",\"startedAt\":").append(System.currentTimeMillis())
                .append(",\"rounds\":").append(roundsPerTopic).append(",\"topics\":[");
        for (int i = 0; i < topics.size(); i++) {
            json.append(i > 0 ? "," : "").append(quote(topics.get(i)));
        }
        json.append("],\"members\":[");
        for (int i = 0; i < agents.size(); i++) {
            json.append(i > 0 ? "," : "").append("{").append(memberFields(agents.get(i))).append("}");
        }
        return json.append("]}").toString();
    }

    private static String memberFields(Agent agent) {
        return "\"name\":" + quote(agent.getName())
                + ",\"party\":" + quote(agent.getParty().name())
                + ",\"partyName\":" + quote(agent.getParty().getDisplayName())
                + ",\"strategy\":" + quote(agent.getStrategy().name());
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            message += " (" + e.getCause().getMessage() + ")";
        }
        return message;
    }

    static String quote(String value) {
        return value == null ? "null" : "\"" + Json.escape(value) + "\"";
    }
}

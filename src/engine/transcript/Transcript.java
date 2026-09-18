package engine.transcript;

import java.util.List;

/** Immutable snapshot for agents, evaluators, display, and public export. */
public record Transcript(List<DebateEvent> events) {
    public Transcript {
        events = List.copyOf(events);
        long expected = 1;
        for (DebateEvent event : events) {
            if (event.turnId() != expected++) throw new IllegalArgumentException("Transcript turn IDs must be contiguous from 1");
        }
    }

    public static Transcript empty() { return new Transcript(List.of()); }
}

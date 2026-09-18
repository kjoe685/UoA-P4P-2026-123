package engine.io;

import engine.transcript.DebateEvent;

/** Output sinks receive only public events. */
@FunctionalInterface
public interface EngineOutput {
    void displayEvent(DebateEvent event);
}

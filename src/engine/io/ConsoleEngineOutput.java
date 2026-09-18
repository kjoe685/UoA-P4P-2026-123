package engine.io;

import engine.transcript.DebateEvent;
import engine.transcript.EventType;

public final class ConsoleEngineOutput implements EngineOutput {
    @Override
    public void displayEvent(DebateEvent event) {
        String name = event.speaker() == null ? "The Speaker" : event.speaker().name();
        if (event.type() == EventType.INTERJECTION) name += " (interjecting)";
        System.out.println("[" + name + "]");
        System.out.println(event.text());
        System.out.println();
    }
}

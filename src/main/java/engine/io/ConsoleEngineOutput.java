package engine.io;

import engine.transcript.PublicEvent;
public final class ConsoleEngineOutput implements EngineOutput {
    @Override public void publicEvent(PublicEvent event) {
        String speaker=event.speaker()==null ? "The Speaker" : event.speaker().name();
        System.out.println("["+event.id()+"] "+speaker+": "+event.text());
    }
}

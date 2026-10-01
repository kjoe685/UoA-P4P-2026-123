package engine.io;

import engine.transcript.*;

/** Public evidence and operational progress are distinct callbacks. */
public interface EngineOutput {
    void publicEvent(PublicEvent event);
    default void speakerCalled(Participant speaker,boolean interjection) { }
}

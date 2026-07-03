package engine.io;

public class ConsoleEngineOutput implements EngineOutput {

    @Override
    public void displayMessage(String persona, String message) {
        System.out.println("[" + persona + "]");
        System.out.println(message);
        System.out.println();
    }
}

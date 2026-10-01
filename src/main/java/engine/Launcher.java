package engine;

import engine.web.WebServer;
import java.util.Arrays;

/** Common launch entry point; the shell launchers own tool installation. */
public final class Launcher {
    private Launcher() { }
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "serve" : args[0];
        switch (mode) {
            case "serve", "web" -> {
                if (mode.equals("web")) System.setProperty("parliament.openBrowser", "true");
                WebServer.main(Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
            }
            case "cli" -> Main.main(Arrays.copyOfRange(args, 1, args.length));
            default -> throw new IllegalArgumentException("Use serve or cli");
        }
    }
}

package engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path emptyResources;

    @Test void validationAndHelpNeedNeitherInteractiveInputNorCredentials() {
        assertEquals(0, Main.execute(new String[]{"--validate-config"}));
        assertEquals(0, Main.execute(new String[]{"--help"}));
    }

    @Test void invalidResourcesAndArgumentsReturnNonzeroStatus() {
        assertEquals(2, Main.execute(new String[]{"--resources", emptyResources.toString(), "--validate-config"}));
        assertEquals(2, Main.execute(new String[]{"--resources"}));
        assertEquals(2, Main.execute(new String[]{"--unknown"}));
    }
}

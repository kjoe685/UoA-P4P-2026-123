package engine.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileTextReader {

    public String readText(String fileName) {
        try {
            return Files.readString(Path.of(fileName));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + fileName, e);
        }
    }
}
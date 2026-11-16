package de.nes.plugin.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private final Path dataDirectoy;

    public ConfigManager(Path dataDirectoy) throws IOException {
        this.dataDirectoy = dataDirectoy;

        ensureConfigFile();
    }   

    private Path ensureConfigFile() throws IOException {
        Files.createDirectories(dataDirectoy);
        Path file = dataDirectoy.resolve("config.toml");

        if (Files.exists(file)) {
            return file;
        }
        
        try (InputStream in = getClass().getResourceAsStream("/config.toml")) {
            if (in != null) {
                Files.copy(in, file);
            }
        }

        return file;
    }
}

package de.nes.plugin.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private final Path dataDirectoy;
    private final Path configPath;

    public ConfigManager(Path dataDirectoy) throws IOException {
        this.dataDirectoy = dataDirectoy;

        this.configPath = ensureConfigFile();
    }   

    private Path ensureConfigFile() throws IOException {
        Files.createDirectories(dataDirectoy);
        Path file = dataDirectoy.resolve("config.toml");

        if (Files.exists(file)) {
            return file;
        }
        
        try (InputStream in = getClass().getResourceAsStream("/config.toml")) {
            if (in != null) {
                throw new IllegalStateException("config.toml missing from plugin jar!");
            }
        }

        return file;
    }
}

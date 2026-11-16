package de.nes.plugin.config;

import java.nio.file.Path;
import java.util.Map;

import de.nes.core.model.Template;

public record NesConfig(
    String ownerId,
    String publicHost,
    String defaultHost,
    ShutdownBehavior shutdownBehavior,
    Path templatesRoot,
    Path instancesRoot,
    DockerSettings docker,
    Map<String, Template> templates
) {}
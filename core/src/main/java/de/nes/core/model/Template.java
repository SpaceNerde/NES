package de.nes.core.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public record Template(
    String id, 
    String image, 
    int containerPort, 
    String dataVolume,
    Path templateDir,
    long memoryBytes,
    double cpus,
    Duration readyTimeout,
    Duration stopGrace,
    List<String> command,
    Map<String, String> env
) {}
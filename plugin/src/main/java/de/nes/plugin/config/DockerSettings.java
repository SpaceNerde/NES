package de.nes.plugin.config;

public record DockerSettings(
    String host,
    String network,
    int portStart,
    int portEnd
) {}
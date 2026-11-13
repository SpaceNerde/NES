package de.nes.core.provider;

import java.util.UUID;

import de.nes.core.model.InstanceName;

public record ProviderInstance(
    String ref,
    UUID instanceId,
    InstanceName name,
    String templateId,
    String host,
    int port,
    ProviderState state
) {}
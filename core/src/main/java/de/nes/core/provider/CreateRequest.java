package de.nes.core.provider;

import java.nio.file.Path;
import java.util.UUID;

import de.nes.core.model.InstanceName;
import de.nes.core.model.Template;

public record CreateRequest(
    UUID instanceId,
    InstanceName name,
    Template template,
    Path instanceDir,
    String ownerId
) {}
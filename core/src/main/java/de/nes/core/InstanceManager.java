package de.nes.core;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import de.nes.core.model.Instance;
import de.nes.core.model.InstanceName;
import de.nes.core.model.Template;
import de.nes.core.provider.CreateRequest;
import de.nes.core.provider.InstanceProvider;

public class InstanceManager {
    private final InstanceProvider provider;
    private final HashMap<String, Template> templates;
    private final Path instancesRoot;
    private final String ownerId;

    public InstanceManager(
        InstanceProvider provider,
        HashMap<String, Template> templates,
        Path instancesRoot,
        String ownerId
    ) {
        this.provider = provider;
        this.templates = templates;
        this.instancesRoot = instancesRoot;
        this.ownerId = ownerId;
    }

    public CompletableFuture<Instance> create(String templateId, String requestedName) {
            Template template = templates.get(templateId);
            InstanceName name = new InstanceName(
                requestedName != null
                ? requestedName : templateId + "-" + "asdadasd" // TODO: Maybe some random suffix generator?
            );
            
            var request = new CreateRequest(
                UUID.randomUUID(), 
                name, 
                template, 
                instancesRoot.resolve(name.value()), 
                ownerId
            );

            return provider.create(request)
                .thenCompose(created -> null) // wait until server is ready
                .thenApply(ready -> null); // register the server with velocity
    }
}
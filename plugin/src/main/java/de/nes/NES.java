package de.nes;

import java.util.logging.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

@Plugin(
    id = "nes-plugin", 
    name = "Not Enough Servers - NES", 
    version = "1.0.0",
    description = "A plugin to create more server instances", 
    authors = {"Space Nerd"}
)

public class NES {
    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public NES(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }
}

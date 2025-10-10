package com.example.reportsystem.platform.velocity;

import com.example.reportsystem.ReportSystem;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(id = "reportsystem", name = "ReportSystem", version = "3.0.0", authors = {"you"})
public final class VelocityBootstrap {

    private final ReportSystem core;

    public VelocityBootstrap(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        VelocityPlatformAdapter adapter = new VelocityPlatformAdapter(this, proxy, logger, dataDirectory);
        this.core = new ReportSystem(adapter);
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        core.enable();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        core.disable();
    }
}

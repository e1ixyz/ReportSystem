package com.example.reportsystem.platform.bukkit;

import com.example.reportsystem.ReportSystem;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitBootstrap extends JavaPlugin {

    private BukkitPlatformAdapter adapter;
    private ReportSystem core;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Failed to create data folder: " + getDataFolder());
        }
        adapter = new BukkitPlatformAdapter(this);
        core = new ReportSystem(adapter);
        core.enable();
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.disable();
        }
        if (adapter != null) {
            adapter.close();
        }
    }
}

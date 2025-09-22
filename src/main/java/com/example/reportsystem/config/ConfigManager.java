package com.example.reportsystem.config;

import com.example.reportsystem.util.Text;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ConfigManager {
    private final Path dataDir;
    private final Path configPath;
    public ConfigManager(Path dataDir) {
        this.dataDir = dataDir;
        this.configPath = dataDir.resolve("config.yml");
    }

    public PluginConfig loadOrCreate() throws IOException {
        if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
        if (!Files.exists(configPath)) {
            // Write default from resource
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) throw new FileNotFoundException("default config.yml missing in jar");
                Files.copy(in, configPath);
            }
        }
        Yaml yaml = new Yaml();
        try (Reader reader = Files.newBufferedReader(configPath)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yaml.load(reader);
            return ConfigParser.parse(root);
        }
    }

    public void saveRaw(Map<String, Object> map) throws IOException {
        Yaml yaml = new Yaml();
        try (Writer w = Files.newBufferedWriter(configPath)) {
            yaml.dump(map, w);
        }
    }

    private static class ConfigParser {
        @SuppressWarnings("unchecked")
        static PluginConfig parse(Map<String, Object> root) {
            PluginConfig pc = new PluginConfig();
            pc.allowSelfReport = get(root,"allow-self-report", false);
            pc.stackWindowSeconds = get(root,"stack-window-seconds", 600);
            pc.exportHtmlChatlog = get(root,"export-html-chatlog", true);
            pc.htmlExportDir = get(root,"html-export-dir", "html-logs");
            pc.reportsPerPage = get(root,"reports-per-page", 10);
            pc.staffPermission = get(root,"staff-permission", "reportsystem.reports");

            Map<String,Object> msgs = (Map<String,Object>) root.getOrDefault("messages", Map.of());
            pc.messages = msgs;

            Map<String,Object> rtypes = (Map<String,Object>) root.getOrDefault("report-types", Map.of());
            for (String key : rtypes.keySet()) {
                Map<String,Object> t = (Map<String,Object>) rtypes.get(key);
                PluginConfig.ReportTypeDef def = new PluginConfig.ReportTypeDef();
                def.display = (String) t.getOrDefault("display", key);
                Map<String,String> cats = new java.util.LinkedHashMap<>();
                Map<String,Object> rawCats = (Map<String,Object>) t.getOrDefault("categories", Map.of());
                for (String ck : rawCats.keySet()) {
                    cats.put(ck, String.valueOf(rawCats.get(ck)));
                }
                def.categories = cats;
                pc.reportTypes.put(key.toLowerCase(), def);
            }
            Text.setPrefix(pc.msg("prefix", ""));
            return pc;
        }

        private static <T> T get(Map<String,Object> m, String k, T def) {
            Object v = m.get(k);
            return v == null ? def : (T) v;
        }
    }
}

package com.example.reportsystem.storage;

import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class FileReportStorage implements ReportStorage {

    private final Path directory;
    private final Logger log;

    public FileReportStorage(Path directory, Logger log) {
        this.directory = directory;
        this.log = log;
    }

    @Override
    public void init() throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }
    }

    @Override
    public List<StoredReportPayload> loadAll() throws IOException {
        List<StoredReportPayload> payloads = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return payloads;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yml")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String base = name.substring(0, name.length() - 4); // strip .yml
                long id;
                try {
                    id = Long.parseLong(base);
                } catch (NumberFormatException ex) {
                    log.warn("Skipping malformed report file {}", name);
                    continue;
                }
                String yaml = Files.readString(file, StandardCharsets.UTF_8);
                payloads.add(new StoredReportPayload(id, yaml));
            }
        }
        return payloads;
    }

    @Override
    public void save(long id, String yamlPayload) throws IOException {
        Path target = directory.resolve(id + ".yml");
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(yamlPayload);
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public String backendKey() {
        return "filesystem";
    }
}

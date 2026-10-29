package com.vcrd.component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class LocalDocumentStorage implements DocumentStorage {

    private final Path root;

    public LocalDocumentStorage(@Value("${vcrd.storage.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String store(byte[] content, String extension) {
        LocalDate today = LocalDate.now();
        String key = "%d/%02d/%s.%s".formatted(today.getYear(), today.getMonthValue(), UUID.randomUUID(), extension);
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store document", e);
        }
        return key;
    }

    @Override
    public Resource load(String storageKey) {
        return new FileSystemResource(resolve(storageKey));
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}

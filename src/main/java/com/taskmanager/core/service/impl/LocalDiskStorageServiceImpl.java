package com.taskmanager.core.service.impl;

import com.taskmanager.core.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorageServiceImpl implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskStorageServiceImpl.class);

    private final String uploadDir = "uploads";

    @Override
    public String store(UUID taskId, MultipartFile file) {
        try {
            File taskDir = new File(uploadDir, taskId.toString());
            if (!taskDir.exists() && !taskDir.mkdirs()) {
                throw new RuntimeException("Could not create upload directory for task " + taskId);
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "unnamed_file";
            }

            String safeFilename = UUID.randomUUID().toString().substring(0, 8) + "_"
                    + originalFilename.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");

            Path targetLocation = taskDir.toPath().resolve(safeFilename);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            return "local:" + targetLocation;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to store file " + file.getOriginalFilename(), ex);
        }
    }

    @Override
    public void delete(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith("local:")) return;
        try {
            Path root = Path.of(uploadDir).toAbsolutePath().normalize();
            Path target = Path.of(fileUrl.substring(6)).toAbsolutePath().normalize();
            // Never delete anything outside the uploads directory.
            if (!target.startsWith(root)) {
                log.warn("Refusing to delete file outside upload dir: {}", target);
                return;
            }
            Files.deleteIfExists(target);
        } catch (Exception e) {
            log.warn("Failed to delete local file {}: {}", fileUrl, e.getMessage());
        }
    }
}
package com.taskmanager.core.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface StorageService {
    /**
     * Stores a file and returns its storage reference ("local:<path>" or "s3:<key>").
     */
    String store(UUID taskId, MultipartFile file);

    /**
     * Best-effort removal of a previously stored file by its storage reference.
     * Never throws; failures are logged.
     */
    void delete(String fileUrl);
}
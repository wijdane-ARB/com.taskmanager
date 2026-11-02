package com.taskmanager.core.controller;

import com.taskmanager.core.entity.Attachment;
import com.taskmanager.core.entity.Task;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.repository.TaskRepository;
import com.taskmanager.core.service.TeamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * FIX (runtime 500): the old version called attachmentRepository.findById()
 * and then walked task.getProject().getTeam() outside any transaction with
 * spring.jpa.open-in-view=false, which throws LazyInitializationException on
 * every download. It now loads the task through findByIdAndStatusNot(), whose
 * entity graph fetches project, project.team and attachments in one query,
 * and looks the attachment up in that already-loaded collection.
 *
 * Also: "no access" now returns the same 404 as "not found" (no enumeration),
 * and the Content-Disposition filename is built with ContentDisposition so
 * quotes/CRLF in a stored filename cannot break out of the header.
 */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/attachments")
public class AttachmentController {

    private final TaskRepository taskRepository;
    private final TeamService teamService;
    private final S3Presigner s3Presigner;

    @Value("${storage.type:local}")
    private String storageType;

    @Value("${storage.s3.bucket:}")
    private String bucketName;

    public AttachmentController(TaskRepository taskRepository,
                                TeamService teamService,
                                @Autowired(required = false) S3Presigner s3Presigner) {
        this.taskRepository = taskRepository;
        this.teamService = teamService;
        this.s3Presigner = s3Presigner;
    }

    @GetMapping("/{attachmentId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> downloadAttachment(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId,
            @PathVariable UUID attachmentId
    ) {
        Task task = taskRepository.findByIdAndStatusNot(taskId, TaskStatus.CANCELLED)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found"));

        if (!teamService.hasTeamAccess(task.getProject().getTeam().getId(), currentUserId)) {
            throw new ResourceNotFoundException("Attachment not found");
        }

        Attachment attachment = task.getAttachments().stream()
                .filter(a -> a.getId().equals(attachmentId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found"));

        String fileUrl = attachment.getFileUrl();

        if (fileUrl.startsWith("s3:") && s3Presigner != null) {
            String objectKey = fileUrl.substring(3);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(getObjectRequest)
                    .build();

            String presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toString();
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(presignedUrl))
                    .build();

        } else if (fileUrl.startsWith("local:")) {
            File file = new File(fileUrl.substring(6));
            if (!file.exists()) {
                throw new ResourceNotFoundException("File not found on disk.");
            }
            Resource resource = new FileSystemResource(file);
            String disposition = ContentDisposition.attachment()
                    .filename(sanitizeFilename(attachment.getFileName()), StandardCharsets.UTF_8)
                    .build()
                    .toString();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(resource);
        }

        throw new IllegalStateException("Unsupported storage backend or missing S3 presigner.");
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "download";
        return name.replaceAll("[\\r\\n\"\\\\/]", "_");
    }
}
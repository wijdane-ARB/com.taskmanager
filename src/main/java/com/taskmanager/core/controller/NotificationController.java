package com.taskmanager.core.controller;

import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.NotificationResponse;
import com.taskmanager.core.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getNotifications(
            @AuthenticationPrincipal UUID currentUserId,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getNotificationsForUser(currentUserId, pageable)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Long>> getUnreadCount(@AuthenticationPrincipal UUID currentUserId) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getUnreadCount(currentUserId)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID id) {
        notificationService.markAsRead(currentUserId, id);
        return ResponseEntity.ok(ApiResponse.ok("Notification marked as read", null));
    }

    /** POST /api/v1/notifications/read-all — returns how many were updated. */
    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Integer>> markAllAsRead(@AuthenticationPrincipal UUID currentUserId) {
        int updated = notificationService.markAllAsRead(currentUserId);
        return ResponseEntity.ok(ApiResponse.ok("All notifications marked as read", updated));
    }
}
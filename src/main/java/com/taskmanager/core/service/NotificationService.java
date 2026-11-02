package com.taskmanager.core.service;

import com.taskmanager.core.dto.response.NotificationResponse;
import com.taskmanager.core.entity.Notification;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.event.NotificationSendEvent;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.repository.NotificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationService(NotificationRepository notificationRepository, ApplicationEventPublisher eventPublisher) {
        this.notificationRepository = notificationRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void createNotification(User recipient, String title, String message, String type) {
        Notification notification = new Notification(recipient, title, message, type);
        notification = notificationRepository.save(notification);

        // WebSocket push is published AFTER_COMMIT by TransactionalEventsListener
        eventPublisher.publishEvent(new NotificationSendEvent(
                recipient.getId(),
                NotificationResponse.from(notification)
        ));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotificationsForUser(UUID userId, Pageable pageable) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId, pageable)
                .map(NotificationResponse::from);
    }

    /**
     * FIX: previously returned 200 for unknown IDs and 403 for someone else's
     * notification (which leaked that the ID exists). Both are now the same 404.
     */
    @Transactional
    public void markAsRead(UUID callerId, UUID notificationId) {
        Notification n = notificationRepository.findByIdAndRecipientId(notificationId, callerId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found."));
        n.setRead(true);
    }

    @Transactional
    public int markAllAsRead(UUID callerId) {
        return notificationRepository.markAllReadForUser(callerId);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByRecipientIdAndReadFalse(userId);
    }
}
package com.taskmanager.core.event;

import com.taskmanager.core.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
public class TransactionalEventsListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionalEventsListener.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final EmailService emailService;

    public TransactionalEventsListener(
            @org.springframework.context.annotation.Lazy SimpMessagingTemplate messagingTemplate,
            EmailService emailService) {
        this.messagingTemplate = messagingTemplate;
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleTaskChange(TaskChangeEvent event) {
        try {
            String destination = "/topic/teams/" + event.teamId() + "/tasks";
            Map<String, Object> payload = Map.of(
                    "type", event.type(),
                    "taskId", event.taskId().toString(),
                    "projectId", event.projectId().toString(),
                    "task", event.taskPayload()
            );
            messagingTemplate.convertAndSend(destination, (Object) payload, (Map<String, Object>) null);
            log.debug("STOMP event broadcast AFTER_COMMIT to {}", destination);
        } catch (Exception e) {
            log.error("Failed to broadcast STOMP task update after commit: {}", e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleNotificationSend(NotificationSendEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(
                    event.recipientId().toString(),
                    "/queue/notifications",
                    event.notification()
            );
            log.debug("STOMP notification sent AFTER_COMMIT to user {}", event.recipientId());
        } catch (Exception e) {
            log.error("Failed to send STOMP notification after commit: {}", e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleSendEmail(SendEmailEvent event) {
        try {
            switch (event.type()) {
                case VERIFICATION_CODE -> emailService.sendVerificationCode(event.toEmail(), event.displayName(), event.extra());
                case PASSWORD_RESET_CODE -> emailService.sendPasswordResetCode(event.toEmail(), event.displayName(), event.extra());
                case TEAM_INVITE -> emailService.sendTeamInvite(event.toEmail(), event.extra(), event.displayName()); // extra = teamName
            }
            log.debug("Email event processed AFTER_COMMIT for {}", event.toEmail());
        } catch (Exception e) {
            log.error("Failed to send email after commit: {}", e.getMessage());
        }
    }
}
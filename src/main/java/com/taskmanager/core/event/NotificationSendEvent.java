package com.taskmanager.core.event;

import com.taskmanager.core.dto.response.NotificationResponse;
import java.util.UUID;

public record NotificationSendEvent(
        UUID recipientId,
        NotificationResponse notification
) {}
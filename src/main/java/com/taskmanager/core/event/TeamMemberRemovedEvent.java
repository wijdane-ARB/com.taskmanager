package com.taskmanager.core.event;

import java.util.UUID;

public record TeamMemberRemovedEvent(UUID teamId, UUID memberUserId) {}

package com.taskmanager.core.event;

public record SendEmailEvent(
        String toEmail,
        String displayName,
        String extra, // code or inviterName
        EmailType type
) {
    public enum EmailType {
        VERIFICATION_CODE,
        PASSWORD_RESET_CODE,
        TEAM_INVITE
    }
}
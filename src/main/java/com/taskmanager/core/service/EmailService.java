// FILE: EmailService.java
package com.taskmanager.core.service;

public interface EmailService {
    void sendVerificationCode(String toEmail, String displayName, String code);
    void sendPasswordResetCode(String toEmail, String displayName, String code);
}
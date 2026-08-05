// PATH: src/main/java/com/taskmanager/core/service/impl/EmailServiceImpl.java
package com.taskmanager.core.service.impl;

import com.taskmanager.core.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);
    private final JavaMailSender mailSender;

    @Value("${app.mail.from:no-reply@taskmanager.app}")
    private String fromAddress;

    @Value("${app.mail.code-expiry-minutes:15}")
    private int codeExpiryMinutes;

    public EmailServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    @Async // <--- Runs in background thread
    public void sendVerificationCode(String toEmail, String displayName, String code) {
        String subject = "Confirm your Taskmanager account";
        String body = """
                Hi %s,
                Your verification code is: %s
                This code expires in %d minutes.
                """.formatted(displayName, code, codeExpiryMinutes);
        send(toEmail, subject, body);
    }

    @Override
    @Async // <--- Runs in background thread
    public void sendPasswordResetCode(String toEmail, String displayName, String code) {
        String subject = "Reset your Taskmanager password";
        String body = """
                Hi %s,
                Your password reset code is: %s
                This code expires in %d minutes.
                """.formatted(displayName, code, codeExpiryMinutes);
        send(toEmail, subject, body);
    }

    private void send(String toEmail, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", toEmail, e.getMessage());
        }
    }
}
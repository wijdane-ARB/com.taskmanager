// PATH: src/main/java/com/taskmanager/core/service/impl/EmailServiceImpl.java
package com.taskmanager.core.service.impl;

import com.taskmanager.core.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);
    private final JavaMailSender mailSender;
    private final boolean devFallback;

    @Value("${app.mail.from:no-reply@taskmanager.app}")
    private String fromAddress;

    @Value("${app.mail.code-expiry-minutes:15}")
    private int codeExpiryMinutes;

    public EmailServiceImpl(JavaMailSender mailSender, Environment env) {
        this.mailSender = mailSender;
        // SECURITY FIX: the console fallback prints verification / reset codes
        // in plaintext. It is now limited to the "dev" and "local" profiles
        // (run with --spring.profiles.active=dev) so a production SMTP outage
        // can never leak codes into the logs.
        this.devFallback = env.acceptsProfiles(Profiles.of("dev", "local"));
    }

    @Override
    @Async
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
    @Async
    public void sendPasswordResetCode(String toEmail, String displayName, String code) {
        String subject = "Reset your Taskmanager password";
        String body = """
                Hi %s,
                Your password reset code is: %s
                This code expires in %d minutes.
                """.formatted(displayName, code, codeExpiryMinutes);
        send(toEmail, subject, body);
    }

    @Override
    @Async
    public void sendTeamInvite(String toEmail, String teamName, String inviterName) {
        String subject = "You've been invited to join " + teamName;
        String body = """
                Hi,
                %s has invited you to join the team '%s' on Taskmanager.
                Login to your account to accept the invitation.
                """.formatted(inviterName, teamName);
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
            if (devFallback) {
                log.info("--- [DEV FALLBACK] ---");
                log.info("To: {}", toEmail);
                log.info("Subject: {}", subject);
                log.info("Body: \n{}", body);
                log.info("----------------------");
            }
        }
    }
}
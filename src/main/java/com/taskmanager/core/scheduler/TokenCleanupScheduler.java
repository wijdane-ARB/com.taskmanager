// FILE: TokenCleanupScheduler.java
package com.taskmanager.core.scheduler;

import com.taskmanager.core.repository.RefreshTokenRepository;
import com.taskmanager.core.repository.VerificationCodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class TokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupScheduler.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationCodeRepository verificationCodeRepository;

    public TokenCleanupScheduler(
            RefreshTokenRepository refreshTokenRepository,
            VerificationCodeRepository verificationCodeRepository
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.verificationCodeRepository = verificationCodeRepository;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC") // 3:00 AM UTC every day
    @Transactional
    public void purgeExpiredTokens() {
        log.info("Starting expired refresh token cleanup...");
        refreshTokenRepository.deleteAllExpiredBefore(Instant.now());
        log.info("Expired refresh token cleanup complete.");

        log.info("Starting expired verification code cleanup...");
        verificationCodeRepository.deleteAllExpiredBefore(Instant.now());
        log.info("Expired verification code cleanup complete.");
    }
}
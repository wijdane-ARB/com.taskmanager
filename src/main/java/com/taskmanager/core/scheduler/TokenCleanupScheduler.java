package com.taskmanager.core.scheduler;

import com.taskmanager.core.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Housekeeping job that removes expired refresh tokens from the DB.
 *
 * Without this, the refresh_tokens table grows indefinitely — one row per
 * login, across every device, for every user. At scale this becomes a
 * significant storage and query-performance problem.
 *
 * Runs at 3:00 AM UTC daily — low-traffic window.
 * Enable @EnableScheduling in Application.java or a @Configuration class.
 *
 * For multi-instance deployments (k8s), add ShedLock or a similar distributed
 * lock library to prevent all instances from running this simultaneously.
 */
@Component
public class TokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupScheduler.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public TokenCleanupScheduler(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(cron = "0 0 3 * * *") // 3:00 AM UTC every day
    @Transactional
    public void purgeExpiredTokens() {
        log.info("Starting expired refresh token cleanup...");
        refreshTokenRepository.deleteAllExpiredBefore(Instant.now());
        log.info("Expired refresh token cleanup complete.");
    }
}

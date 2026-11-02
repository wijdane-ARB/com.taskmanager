package com.taskmanager.core.service;

import com.taskmanager.core.dto.request.VerifyEmailRequest;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.exception.InvalidCodeException;
import com.taskmanager.core.repository.RefreshTokenRepository;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.repository.VerificationCodeRepository;
import com.taskmanager.core.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private VerificationCodeRepository verificationCodeRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    @DisplayName("verifyEmail fails when user is already verified (no shortcut hijack)")
    void testVerifyEmailThrowsIfAlreadyVerified() {
        User user = new User("John", "john@example.com", "hash");
        user.setEmailVerified(true);

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));

        VerifyEmailRequest request = new VerifyEmailRequest("john@example.com", "000000");

        assertThrows(InvalidCodeException.class, () -> {
            authService.verifyEmail(request, "device");
        });
    }
}
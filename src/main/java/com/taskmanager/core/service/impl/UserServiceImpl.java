package com.taskmanager.core.service.impl;

import com.taskmanager.core.dto.request.UpdateUserRequest;
import com.taskmanager.core.dto.response.UserResponse;
import com.taskmanager.core.entity.User;
import com.taskmanager.core.exception.ResourceNotFoundException;
import com.taskmanager.core.repository.UserRepository;
import com.taskmanager.core.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserResponse getMe(UUID userId) {
        return UserResponse.from(findUserOrThrow(userId));
    }

    @Override
    @Transactional
    public UserResponse updateMe(UUID userId, UpdateUserRequest request) {
        log.debug("Updating profile for user {}", userId);

        User user = findUserOrThrow(userId);

        // PATCH semantics — only apply non-null fields
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }

        User saved = userRepository.save(user);
        log.info("Profile updated for user {}", userId);

        return UserResponse.from(saved);
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + userId
                ));
    }
}

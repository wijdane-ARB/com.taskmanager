package com.taskmanager.core.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(

        @NotBlank(message = "Comment cannot be empty")
        @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
        String content

) {}
package com.aiinterviewer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^\\w{4,32}$", message = "需为 4-32 位字母/数字/下划线")
        String username,
        @NotBlank
        @Size(min = 6, max = 64, message = "长度需在 6-64 之间")
        String password) {
}

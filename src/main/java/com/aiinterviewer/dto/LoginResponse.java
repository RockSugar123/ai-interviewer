package com.aiinterviewer.dto;

public record LoginResponse(String token, Long userId, String username) {
}

package com.aiinterviewer.controller;

import com.aiinterviewer.common.ApiResponse;
import com.aiinterviewer.dto.ResumeFileResponse;
import com.aiinterviewer.infra.web.AuthInterceptor;
import com.aiinterviewer.service.ResumeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    @PostMapping
    public ApiResponse<ResumeFileResponse> upload(@RequestParam("file") MultipartFile file,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(resumeService.upload(AuthInterceptor.currentUserId(request), file));
    }

    @GetMapping
    public ApiResponse<List<ResumeFileResponse>> list(HttpServletRequest request) {
        return ApiResponse.ok(resumeService.list(AuthInterceptor.currentUserId(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        resumeService.delete(AuthInterceptor.currentUserId(request), id);
        return ApiResponse.ok(null);
    }
}

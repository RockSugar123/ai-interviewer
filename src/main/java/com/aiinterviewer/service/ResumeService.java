package com.aiinterviewer.service;

import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import com.aiinterviewer.dto.ResumeFileResponse;
import com.aiinterviewer.infra.persistence.entity.ResumeFile;
import com.aiinterviewer.infra.persistence.mapper.ResumeFileMapper;
import com.aiinterviewer.rag.RagIndexService;
import com.aiinterviewer.rag.RagProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 简历文件（FR-10）：上传校验 → 落盘 → 记录 PENDING → 投递 MQ 索引任务（阶段 5 起，ResumeIndexListener 串行消费；
 * MQ 停用时同步降级）。上传即刻返回，前端轮询 parseStatus；失败原因见 errorMsg。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "doc", "docx");
    private static final long MAX_SIZE = 10 * 1024 * 1024;

    private final ResumeFileMapper resumeFileMapper;
    private final RagIndexService ragIndexService;
    private final RagProperties ragProperties;
    private final MqProperties mqProperties;
    private final RocketMQTemplate rocketMQTemplate;

    public ResumeFileResponse upload(long userId, MultipartFile file) {
        if (!ragProperties.enabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历功能未开启");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能超过 10MB");
        }
        String ext = extOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持 PDF/DOC/DOCX");
        }
        Path target;
        try {
            Path dir = Path.of(ragProperties.uploadDir(), String.valueOf(userId));
            Files.createDirectories(dir);
            target = dir.resolve(UUID.randomUUID() + "." + ext);
            file.transferTo(target.toAbsolutePath());
        } catch (IOException e) {
            log.error("简历落盘失败 [userId={}]", userId, e);
            throw new BusinessException(ErrorCode.INTERNAL, "文件保存失败");
        }
        ResumeFile entity = new ResumeFile();
        entity.setUserId(userId);
        entity.setFileName(file.getOriginalFilename());
        entity.setFilePath(target.toString());
        entity.setFileSize(file.getSize());
        entity.setParseStatus(ResumeFile.STATUS_PENDING);
        resumeFileMapper.insert(entity);
        if (mqProperties.enabled()) {
            // 消息体用字符串 id（ResumeIndexListener 单线程串行消费，保证 SimpleVectorStore 写安全）
            rocketMQTemplate.syncSend(mqProperties.indexTopic(), String.valueOf(entity.getId()));
            log.info("简历索引任务已投递 MQ [id={}]", entity.getId());
        } else {
            // MQ 停用：同步降级（阶段 4 行为，上传耗时变长）
            ragIndexService.indexResume(entity.getId());
        }
        log.info("简历上传 [id={} userId={} file={} size={}]", entity.getId(), userId,
                entity.getFileName(), entity.getFileSize());
        return toResponse(entity);
    }

    public List<ResumeFileResponse> list(long userId) {
        return resumeFileMapper.selectList(new LambdaQueryWrapper<ResumeFile>()
                        .eq(ResumeFile::getUserId, userId)
                        .orderByDesc(ResumeFile::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /** 删除：向量块 + 物理文件 + 记录（物理删除；已建会话的引用仅表示历史挂载） */
    public void delete(long userId, Long id) {
        getOwned(id, userId);
        ragIndexService.deleteResumeVectors(userId, id);
        ResumeFile file = resumeFileMapper.selectById(id);
        resumeFileMapper.deleteById(id);
        if (file != null && file.getFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(file.getFilePath()));
            } catch (IOException e) {
                log.warn("简历物理文件删除失败（记录已删） [path={}]", file.getFilePath(), e);
            }
        }
        log.info("简历删除 [id={} userId={}]", id, userId);
    }

    /** 归属校验（会话挂载简历时用）：非本人资源按不存在处理 */
    public ResumeFile getOwned(Long id, long userId) {
        ResumeFile file = resumeFileMapper.selectById(id);
        if (file == null || !file.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历不存在");
        }
        return file;
    }

    private ResumeFileResponse toResponse(ResumeFile f) {
        return new ResumeFileResponse(f.getId(), f.getFileName(), f.getFileSize(),
                f.getParseStatus(), f.getErrorMsg(),
                f.getCreatedAt() == null ? LocalDateTime.now() : f.getCreatedAt());
    }

    private String extOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

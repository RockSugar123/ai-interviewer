package com.aiinterviewer.service;

import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import com.aiinterviewer.dto.LoginRequest;
import com.aiinterviewer.dto.LoginResponse;
import com.aiinterviewer.dto.RegisterRequest;
import com.aiinterviewer.dto.UserResponse;
import com.aiinterviewer.infra.auth.JwtUtil;
import com.aiinterviewer.infra.persistence.entity.User;
import com.aiinterviewer.infra.persistence.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public void register(RegisterRequest req) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.username()));
        if (count > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同名的兜底（uk_username 唯一键）
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }
        log.info("用户注册成功 [userId={} username={}]", user.getId(), user.getUsername());
    }

    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.username()));
        if (user == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            // 不区分“用户不存在/密码错误”，避免泄露账号存在性
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }
        return new LoginResponse(jwtUtil.issue(user.getId(), user.getUsername()),
                user.getId(), user.getUsername());
    }

    public UserResponse me(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt());
    }
}

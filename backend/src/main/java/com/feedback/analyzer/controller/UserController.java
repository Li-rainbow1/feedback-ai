package com.feedback.analyzer.controller;

import com.feedback.analyzer.entity.User;
import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.model.vo.UserVO;
import com.feedback.analyzer.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private static final String DEFAULT_ROLE = "admin";

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public ApiResult<List<UserVO>> list() {
        return ApiResult.success(userRepo.findAll().stream().map(this::toVO).toList());
    }

    @GetMapping("/{id}")
    public ApiResult<UserVO> getById(@PathVariable Long id) {
        return ApiResult.success(toVO(userRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"))));
    }

    @PostMapping
    public ApiResult<UserVO> create(@Valid @RequestBody User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setRole(DEFAULT_ROLE);
        return ApiResult.success(toVO(userRepo.save(user)));
    }

    @PutMapping("/{id}")
    public ApiResult<UserVO> update(@PathVariable Long id, @Valid @RequestBody User user) {
        User existing = userRepo.findById(id).orElseThrow(() -> new RuntimeException("用户不存在"));
        existing.setUsername(user.getUsername());
        existing.setNickname(user.getNickname());
        existing.setRole(DEFAULT_ROLE);
        existing.setEnabled(user.getEnabled());
        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            existing.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        return ApiResult.success(toVO(userRepo.save(existing)));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        User target = userRepo.findById(id).orElseThrow(() -> new RuntimeException("用户不存在"));
        String currentUsername = String.valueOf(request.getAttribute("username"));
        if (target.getUsername().equals(currentUsername)) {
            throw new IllegalArgumentException("不能删除当前登录账号");
        }
        if (Boolean.TRUE.equals(target.getEnabled()) && userRepo.countByEnabledTrue() <= 1) {
            throw new IllegalArgumentException("至少需要保留一个启用账号");
        }
        userRepo.delete(target);
        return ApiResult.success();
    }

    private UserVO toVO(User user) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .role(user.getRole())
                .enabled(user.getEnabled())
                .createdAt(user.getCreatedAt())
                .build();
    }
}

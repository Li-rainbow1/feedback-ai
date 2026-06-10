package com.feedback.analyzer.controller;

import com.feedback.analyzer.config.JwtUtil;
import com.feedback.analyzer.entity.User;
import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("密码错误");
        }
        if (!user.getEnabled()) {
            throw new RuntimeException("账号已禁用");
        }
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        return ApiResult.success(Map.of(
                "token", token,
                "username", user.getUsername(),
                "nickname", user.getNickname() != null ? user.getNickname() : user.getUsername(),
                "role", user.getRole()
        ));
    }

    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me(@RequestHeader("X-Token") String token) {
        if (!jwtUtil.validateToken(token)) {
            return ApiResult.error(401, "未登录");
        }
        return ApiResult.success(Map.of(
                "username", jwtUtil.getUsername(token),
                "role", jwtUtil.getRole(token)
        ));
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout() {
        return ApiResult.success();
    }
}

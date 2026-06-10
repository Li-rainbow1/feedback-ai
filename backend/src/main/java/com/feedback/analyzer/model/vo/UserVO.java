package com.feedback.analyzer.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String role;
    private Boolean enabled;
    private LocalDateTime createdAt;
}

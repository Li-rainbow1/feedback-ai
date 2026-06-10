package com.feedback.analyzer.config;

import com.feedback.analyzer.entity.User;
import com.feedback.analyzer.repository.FeedbackIssueEsRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.UserRepository;
import com.feedback.analyzer.service.FeedbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepo;
    private final FeedbackIssueRepository issueRepo;
    private final FeedbackIssueEsRepository issueEsRepo;
    private final FeedbackService feedbackService;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;
    private final String adminNickname;

    public DataInitializer(UserRepository userRepo, FeedbackIssueRepository issueRepo,
                           FeedbackIssueEsRepository issueEsRepo, FeedbackService feedbackService,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.admin.username:admin}") String adminUsername,
                           @Value("${app.admin.password:}") String adminPassword,
                           @Value("${app.admin.nickname:管理员}") String adminNickname) {
        this.userRepo = userRepo;
        this.issueRepo = issueRepo;
        this.issueEsRepo = issueEsRepo;
        this.feedbackService = feedbackService;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.adminNickname = adminNickname;
    }

    @Override
    public void run(String... args) {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("Startup admin user initialization skipped because app.admin.password is empty");
        } else {
            ensureUser(adminUsername, adminPassword, adminNickname);
        }
        normalizeUserRoles();

        try {
            boolean created = issueEsRepo.ensureIndex();
            long mysqlCount = issueRepo.count();
            long esCount = issueEsRepo.count();
            if (mysqlCount > 0 && (created || esCount == 0)) {
                int reindexed = feedbackService.reindexAllIssues();
                log.info("Startup: reindexed {} issues (MySQL: {}, ES before rebuild: {})", reindexed, mysqlCount, esCount);
            }
        } catch (Exception e) {
            log.warn("Startup reindex check skipped: {}", e.getMessage());
        }
    }

    private void ensureUser(String username, String rawPassword, String nickname) {
        User user = userRepo.findByUsername(username).orElse(null);
        if (user == null) {
            userRepo.save(User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(rawPassword))
                    .nickname(nickname)
                    .role("admin")
                    .enabled(true)
                    .build());
            return;
        }
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            user.setPassword(passwordEncoder.encode(rawPassword));
        }
        if (user.getNickname() == null || user.getNickname().isBlank()) {
            user.setNickname(nickname);
        }
        if (user.getRole() == null || user.getRole().isBlank() || !"admin".equals(user.getRole())) {
            user.setRole("admin");
        }
        if (user.getEnabled() == null) {
            user.setEnabled(true);
        }
        userRepo.save(user);
    }

    private void normalizeUserRoles() {
        userRepo.findAll().forEach(user -> {
            if (!"admin".equals(user.getRole())) {
                user.setRole("admin");
                userRepo.save(user);
            }
        });
    }
}

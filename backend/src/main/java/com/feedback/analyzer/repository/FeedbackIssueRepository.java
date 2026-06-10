package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FeedbackIssueRepository extends JpaRepository<FeedbackIssue, String> {

    List<FeedbackIssue> findByProductId(Long productId);

    Optional<FeedbackIssue> findByRelatedIssue(String relatedIssue);

    Page<FeedbackIssue> findByProductIdAndStatusOrderByReportCountDesc(Long productId, IssueStatusEnum status, Pageable pageable);

    Page<FeedbackIssue> findByProductIdAndSeverityAndStatusOrderByReportCountDesc(Long productId, String severity, IssueStatusEnum status, Pageable pageable);

    long countByProductIdAndCategoryAndLatestReportAtBetween(Long productId,
                                                             FeedbackCategoryEnum category,
                                                             LocalDateTime start,
                                                             LocalDateTime end);

    long countByProductIdAndCategoryAndFirstReportAtBetween(Long productId,
                                                            FeedbackCategoryEnum category,
                                                            LocalDateTime start,
                                                            LocalDateTime end);

    long countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(Long productId,
                                                                        FeedbackCategoryEnum category,
                                                                        String severity,
                                                                        LocalDateTime start,
                                                                        LocalDateTime end);

    default Page<FeedbackIssue> searchIssues(Long productId,
                                             String severity,
                                             IssueStatusEnum status,
                                             FeedbackCategoryEnum category,
                                             String module,
                                             Pageable pageable) {
        return searchIssues(productId, severity, status, category, module,
                List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED), pageable);
    }

    @Query("SELECT i FROM FeedbackIssue i WHERE " +
           "(:productId IS NULL OR i.productId = :productId) AND " +
           "(:severity IS NULL OR i.severity = :severity) AND " +
           "(:status IS NULL OR i.status = :status) AND " +
           "(:status IS NOT NULL OR i.status NOT IN :excludedStatuses) AND " +
           "(:category IS NULL OR i.category = :category) AND " +
           "(:module IS NULL OR i.module = :module) " +
           "ORDER BY i.reportCount DESC")
    Page<FeedbackIssue> searchIssues(@Param("productId") Long productId,
                                      @Param("severity") String severity,
                                      @Param("status") IssueStatusEnum status,
                                      @Param("category") FeedbackCategoryEnum category,
                                      @Param("module") String module,
                                      @Param("excludedStatuses") List<IssueStatusEnum> excludedStatuses,
                                      Pageable pageable);

    List<FeedbackIssue> findByStatusAndUpdatedAtBefore(IssueStatusEnum status, LocalDateTime before);

    @Query("SELECT i.module, SUM(i.reportCount) FROM FeedbackIssue i WHERE i.productId = :productId AND i.createdAt BETWEEN :start AND :end GROUP BY i.module ORDER BY SUM(i.reportCount) DESC")
    List<Object[]> findTopModulesByReportCount(@Param("productId") Long productId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT i FROM FeedbackIssue i WHERE i.productId = :productId AND i.status NOT IN :excludedStatuses ORDER BY i.reportCount DESC")
    List<FeedbackIssue> findTopIssuesByProductId(@Param("productId") Long productId,
                                                 @Param("excludedStatuses") List<IssueStatusEnum> excludedStatuses,
                                                 Pageable pageable);

    @Query("SELECT i FROM FeedbackIssue i WHERE " +
            "i.productId = :productId AND i.category = :category AND i.status NOT IN :excludedStatuses AND " +
            "i.latestReportAt BETWEEN :start AND :end " +
            "ORDER BY CASE " +
            "WHEN i.severity = 'CRITICAL' THEN 0 " +
            "WHEN i.severity = 'HIGH' THEN 1 " +
            "WHEN i.severity = 'MEDIUM' THEN 2 " +
            "WHEN i.severity = 'LOW' THEN 3 " +
            "ELSE 4 END, i.reportCount DESC, i.latestReportAt DESC")
    List<FeedbackIssue> findUrgentIssues(@Param("productId") Long productId,
                                         @Param("category") FeedbackCategoryEnum category,
                                         @Param("excludedStatuses") List<IssueStatusEnum> excludedStatuses,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end,
                                         Pageable pageable);

    @Query("SELECT i FROM FeedbackIssue i WHERE " +
            "i.productId = :productId AND i.category = :category AND i.status NOT IN :excludedStatuses AND i.firstReportAt BETWEEN :start AND :end " +
            "ORDER BY i.firstReportAt DESC")
    List<FeedbackIssue> findRecentNewIssues(@Param("productId") Long productId,
                                            @Param("category") FeedbackCategoryEnum category,
                                            @Param("excludedStatuses") List<IssueStatusEnum> excludedStatuses,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(i) FROM FeedbackIssue i WHERE " +
            "i.productId = :productId AND i.category = :category AND " +
            "i.status NOT IN :excludedStatuses AND " +
            "i.firstReportAt BETWEEN :start AND :end AND " +
            "i.suspectedDuplicates IS NOT NULL AND i.suspectedDuplicates <> ''")
    long countNewIssuesWithSuspectedDuplicates(@Param("productId") Long productId,
                                               @Param("category") FeedbackCategoryEnum category,
                                               @Param("excludedStatuses") List<IssueStatusEnum> excludedStatuses,
                                               @Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);
}

package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    Optional<WeeklyReport> findByWeekStart(LocalDate weekStart);

    boolean existsByWeekStart(LocalDate weekStart);

    boolean existsByProductIdAndWeekStart(Long productId, LocalDate weekStart);

    java.util.Optional<WeeklyReport> findByProductIdAndWeekStart(Long productId, LocalDate weekStart);

    List<WeeklyReport> findByProductIdOrderByWeekStartDesc(Long productId);
}

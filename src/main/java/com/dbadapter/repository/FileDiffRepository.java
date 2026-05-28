package com.dbadapter.repository;

import com.dbadapter.entity.FileDiff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileDiffRepository extends JpaRepository<FileDiff, String> {
    List<FileDiff> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    List<FileDiff> findBySessionIdAndAppliedTrueOrderByCreatedAtDesc(String sessionId);
    void deleteBySessionId(String sessionId);
}

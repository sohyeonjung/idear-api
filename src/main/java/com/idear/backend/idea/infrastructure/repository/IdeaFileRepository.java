package com.idear.backend.idea.infrastructure.repository;

import com.idear.backend.idea.domain.IdeaFile;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IdeaFileRepository extends JpaRepository<IdeaFile, Long> {
    Optional<IdeaFile> findIdeaFileByCommit(String commit);

    /** 대사 대상: 특정 상태로 일정 시간 이상 머문 파일 (마이그레이션 이전 행은 statusChangedAt 이 null) */
    @Query("select f.ideaFileId from IdeaFile f where f.registerStatus = :status "
            + "and (f.statusChangedAt is null or f.statusChangedAt < :before) order by f.ideaFileId")
    List<Long> findIdsByStatusChangedBefore(@Param("status") IdeaFile.RegisterStatus status,
                                            @Param("before") LocalDateTime before, Pageable pageable);

    @Query("select f.ideaFileId from IdeaFile f where f.registerStatus = :status "
            + "and f.statusChangedAt >= :after order by f.ideaFileId")
    List<Long> findIdsByStatusChangedAfter(@Param("status") IdeaFile.RegisterStatus status,
                                           @Param("after") LocalDateTime after, Pageable pageable);
}

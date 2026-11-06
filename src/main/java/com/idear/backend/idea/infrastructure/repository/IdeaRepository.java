package com.idear.backend.idea.infrastructure.repository;

import com.idear.backend.idea.domain.Idea;
import com.idear.backend.idea.domain.IdeaFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdeaRepository extends JpaRepository<Idea, Long> {
	@Query("SELECT vf.ideaVersion.idea FROM IdeaVersionFile vf WHERE vf.ideaFile = :ideaFile")
	Optional<Idea> findIdeaByFile(@Param("ideaFile") IdeaFile ideaFile);

	java.util.List<Idea> findAllByUser(com.idear.backend.user.domain.User user);
}

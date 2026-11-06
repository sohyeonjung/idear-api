package com.idear.backend.idea.application;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idear.backend.idea.domain.Idea;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.domain.IdeaVersion;
import com.idear.backend.idea.infrastructure.repository.IdeaRepository;
import com.idear.backend.user.domain.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아이디어 삭제·회원 탈퇴 시 증빙 데이터 파기.
 *
 * <p>블록체인 기록(commit)은 지울 수 없다. 대신 commit 을 다시 계산하거나 사용자와 연결하는 데 필요한
 * 오프체인 값(원본 파일, fileHash, salt, 서명, 서명 공개키)을 파기해, 온체인 값만으로는
 * 원본이나 정보주체를 특정하기 어렵게 만든다.
 * (기존에는 soft delete 만 해서 이 값들이 DB·S3 에 그대로 남아 있었다.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdeaPurgeService {

	private static final String FILE_UPLOAD_DIR = "file";

	private final IdeaRepository ideaRepository;
	private final FileStorageService fileStorageService;

	@Transactional
	public void purgeIdea(Idea idea) {
		Set<IdeaFile> files = new LinkedHashSet<>();
		for (IdeaVersion version : idea.getVersions()) {
			files.addAll(version.getFiles());
		}
		for (IdeaFile file : files) {
			try {
				fileStorageService.deleteFile(file.getFileName(), FILE_UPLOAD_DIR);
			} catch (Exception e) {
				// S3 삭제 실패가 DB 파기를 막지 않도록 하고, 남은 객체는 로그로 추적한다.
				log.error("[PURGE] S3 object delete failed: ideaFileId={}, key={}/{}",
						file.getIdeaFileId(), FILE_UPLOAD_DIR, file.getFileName(), e);
			}
			file.purge();
		}
		ideaRepository.delete(idea);
		log.info("[PURGE] idea purged: ideaId={}, files={}", idea.getIdeaId(), files.size());
	}

	@Transactional
	public void purgeAllIdeasOf(User user) {
		ideaRepository.findAllByUser(user).forEach(this::purgeIdea);
	}
}

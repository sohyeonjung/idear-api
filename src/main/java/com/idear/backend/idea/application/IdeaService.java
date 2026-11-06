package com.idear.backend.idea.application;

import com.idear.backend.blockchain.application.BlockchainOutboxService;
import com.idear.backend.blockchain.crypto.EcdsaSignatureVerifier;
import com.idear.backend.blockchain.crypto.ProofPayloads;
import com.idear.backend.contest.domain.Contest;
import com.idear.backend.contest.repository.ContestRepository;
import com.idear.backend.global.exception.CustomException;
import com.idear.backend.global.exception.ErrorCode;
import com.idear.backend.idea.domain.Idea;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.domain.IdeaImage;
import com.idear.backend.idea.domain.IdeaVersion;
import com.idear.backend.idea.dto.request.FileSignatureRequest;
import com.idear.backend.idea.dto.request.IdeaCreateRequest;
import com.idear.backend.idea.dto.request.IdeaUpdateRequest;
import com.idear.backend.idea.dto.request.SignatureData;
import com.idear.backend.idea.dto.response.*;
import com.idear.backend.idea.infrastructure.repository.IdeaFileRepository;
import com.idear.backend.idea.infrastructure.repository.IdeaImageRepository;
import com.idear.backend.idea.infrastructure.repository.IdeaRepository;
import com.idear.backend.idea.infrastructure.repository.IdeaVersionRepository;
import com.idear.backend.idea.util.HashUtil;
import com.idear.backend.idea.util.ServerSignatureService;
import com.idear.backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class IdeaService {

	private static final String BLOCKCHAIN_NETWORK = "Sepolia";
	private static final long SEPOLIA_CHAIN_ID = 11155111L;
	private static final int PROOF_VERSION = 2;

	private final IdeaRepository ideaRepository;
	private final IdeaVersionRepository ideaVersionRepository;
	private final IdeaFileRepository ideaFileRepository;
	private final IdeaImageRepository ideaImageRepository;
	private final FileStorageService fileStorageService;
	private final BlockchainOutboxService blockchainOutboxService;
	private final EcdsaSignatureVerifier signatureVerifier;
	private final IdeaPurgeService ideaPurgeService;
	private final ContestRepository contestRepository;
	private final HashUtil hashUtil;
	private final ServerSignatureService serverSignatureService;

	@Value("${blockchain.contract.address}")
	private String contractAddress;

	/** 운영 중 프론트 서명 형식 문제가 생기면 끌 수 있도록 플래그로 둔다 (기본: 켜짐) */
	@Value("${blockchain.signature.verification-enabled:true}")
	private boolean signatureVerificationEnabled = true;

	@Transactional
	public IdeaRegistrationInitResponse initIdeaRegistration(
		User user,
		IdeaCreateRequest request,
		List<MultipartFile> images,
		List<MultipartFile> files
	) throws IOException {
		Contest contest = null;
		if (request.getContestId() != null) {
			contest = contestRepository.findById(request.getContestId())
					.orElseThrow(() -> CustomException.of(ErrorCode.CONTEST_NOT_FOUND));
		}

		LocalDateTime requestedAt = LocalDateTime.now();
		Long requestTimestamp = Instant.now().toEpochMilli();

		Idea idea = Idea.register(
				user,
				contest,
				requestedAt
		);
		idea = ideaRepository.save(idea);

		IdeaVersion initialVersion = IdeaVersion.createInitialVersion(
				request.getTitle(),
				request.getShortDescription(),
				request.getDescription(),
				request.getGithubUrl(),
				request.getFigmaUrl(),
				requestedAt
		);
		idea.addVersion(initialVersion);
		initialVersion = ideaVersionRepository.save(initialVersion);

		processImages(initialVersion, images);
		List<FileHashInfo> fileHashInfoList = processFiles(initialVersion, files, requestTimestamp);

		return IdeaRegistrationInitResponse.builder()
				.ideaId(idea.getIdeaId())
				.requestedAt(requestedAt)
				.files(fileHashInfoList)
				.build();
	}

	@Transactional
	public IdeaRegistrationCompleteResponse submitSignatures(
		User user,
		Long ideaId,
		FileSignatureRequest request
	) {
		Idea idea = ideaRepository.findById(ideaId)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_NOT_FOUND));

		if (!idea.getUser().getUserId().equals(user.getUserId())) {
			throw CustomException.of(ErrorCode.USER_NOT_OWNER);
		}

		String signerPublicKey = user.getPublicKey();
		if (signatureVerificationEnabled && (signerPublicKey == null || signerPublicKey.isBlank())) {
			throw CustomException.of(ErrorCode.PUBLIC_KEY_NOT_REGISTERED);
		}

		List<FileRegistrationResult> registrationResults = new ArrayList<>();

		for (SignatureData signatureData : request.getSignatures()) {
			IdeaFile ideaFile = ideaFileRepository.findById(signatureData.getIdeaFileId())
					.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_FILE_NOT_FOUND));

			// 현재 Idea의 모든 버전에서 이 파일이 포함되어 있는지 확인
			boolean fileOwnedByIdea = idea.getVersions().stream()
					.anyMatch(v -> v.getFiles().contains(ideaFile));
			if (!fileOwnedByIdea) {
				throw CustomException.of(ErrorCode.IDEA_FILE_IDEA_MISMATCH);
			}

			String userSignature = normalizeSignature(signatureData.getUserSignature());

			// 1) 사용자 서명 검증: 등록된 공개키로 "fileHash|timestamp" 에 대한 서명인지 확인
			if (signatureVerificationEnabled) {
				String message = ProofPayloads.userMessage(ideaFile.getFileHash(), ideaFile.getRequestedTimestamp());
				if (!signatureVerifier.verify(signerPublicKey, message, userSignature)) {
					throw CustomException.of(ErrorCode.INVALID_USER_SIGNATURE);
				}
			}

			// 2) 서버 서명: 검증을 통과한 요청이라는 서버 측 보증
			String serverSignature = serverSignatureService.generateServerSignature(
					ideaFile.getCommit(),
					ideaFile.getRequestedTimestamp(),
					userSignature
			);

			// 3) 온체인에는 사용자 서명 원문이 아닌 다이제스트를 기록 (파일-사용자 연결 차단)
			ideaFile.submitUserSignature(
					userSignature,
					serverSignature,
					signerPublicKey,
					ProofPayloads.onChainUserSignatureDigest(userSignature),
					PROOF_VERSION
			);

			// 4) 게이트웨이 호출 대신 Outbox 기록. 같은 트랜잭션으로 커밋된 뒤 릴레이가 전송한다.
			blockchainOutboxService.enqueue(ideaFile);

			registrationResults.add(FileRegistrationResult.of(ideaFile));
		}

		return IdeaRegistrationCompleteResponse.builder()
				.ideaId(ideaId)
				.registeredFiles(registrationResults)
				.totalFiles(registrationResults.size())
				.build();
	}

	@Transactional
	public IdeaUpdateResponse updateIdea(
			User user,
			Long ideaId,
			IdeaUpdateRequest request,
			List<MultipartFile> images,
			List<MultipartFile> files
	) throws IOException {
		Idea idea = ideaRepository.findById(ideaId)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_NOT_FOUND));

		if (!idea.getUser().getUserId().equals(user.getUserId())) {
			throw CustomException.of(ErrorCode.USER_NOT_OWNER);
		}

		IdeaVersion latestVersion = ideaVersionRepository
				.findLatestByIdeaWithFilesAndImages(idea)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_VERSION_NOT_FOUND));

		if (request == null) {
			request = new IdeaUpdateRequest();
		}

		List<Long> deleteFileIds = request.getDeleteFileIds();
		List<Long> deleteImageIds = request.getDeleteImageIds();
		String title = request.getTitle();
		String shortDescription = request.getShortDescription();
		String description = request.getDescription();
		String githubUrl = request.getGithubUrl();
		String figmaUrl = request.getFigmaUrl();

		boolean hasFileChanges = (deleteFileIds != null && !deleteFileIds.isEmpty())
				|| hasNonEmptyFiles(files);

		IdeaVersion targetVersion;
		List<FileHashInfo> fileHashInfoList = new ArrayList<>();
		LocalDateTime updatedAt = LocalDateTime.now();
		Long timestamp = Instant.now().toEpochMilli();

		if (hasFileChanges) {
			Integer maxVersionNumber = ideaVersionRepository
					.findMaxVersionNumberByIdea(idea)
					.orElse(0);

			IdeaVersion newVersion = IdeaVersion.createNewVersion(
					latestVersion,
					maxVersionNumber + 1
			);
			idea.addVersion(newVersion);
			newVersion = ideaVersionRepository.save(newVersion);

			copyImagesFromPreviousVersion(newVersion, latestVersion, deleteImageIds);
			copyFilesFromPreviousVersion(newVersion, latestVersion, deleteFileIds);

			fileHashInfoList = processFiles(newVersion, files, timestamp);
			newVersion.updateMetadataIfNeeded(title, shortDescription, description, githubUrl, figmaUrl);

			targetVersion = newVersion;
		} else {
			latestVersion.updateMetadataIfNeeded(title, shortDescription, description, githubUrl, figmaUrl);
			targetVersion = latestVersion;
		}

		// 메타만 변경 시 이미지 관계 제거
		if (!hasFileChanges && deleteImageIds != null && !deleteImageIds.isEmpty()) {
			targetVersion.removeImagesByIds(deleteImageIds);
		}

		processImages(targetVersion, images);

		return IdeaUpdateResponse.builder()
				.ideaId(ideaId)
				.versionNumber(targetVersion.getVersionNumber())
				.updatedAt(updatedAt)
				.files(fileHashInfoList)
				.build();
	}

	@Transactional(readOnly = true)
	public Page<IdeaSummaryResponse> getIdeasByUser(User user, Pageable pageable, String keyword) {
		Page<IdeaVersion> versionsPage;
		if (keyword == null || keyword.trim().isEmpty()) {
			versionsPage = ideaVersionRepository.findLatestVersionsByUser(user, pageable);
		} else {
			versionsPage = ideaVersionRepository.findLatestVersionsByUserAndKeyword(user, keyword, pageable);
		}

		return versionsPage.map(version -> IdeaSummaryResponse.of(version.getIdea(), version));
	}

	@Transactional(readOnly = true)
	public IdeaWithVersionsResponse getIdeaVersions(User user, Long ideaId) {
		Idea idea = ideaRepository.findById(ideaId)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_NOT_FOUND));

		if (!idea.getUser().getUserId().equals(user.getUserId())) {
			throw CustomException.of(ErrorCode.ACCESS_DENIED);
		}

		List<IdeaVersion> versions = ideaVersionRepository.findAllByIdeaOrderByVersionNumberDesc(idea);
		IdeaFile latestFile = getLatestFile(versions);

		String latestCertificateUrl = getLatestCertificateUrl(latestFile);
		Boolean latestVersionRegistered = checkLatestVersionRegistered(latestFile);

		return IdeaWithVersionsResponse.of(idea, versions, latestCertificateUrl, latestVersionRegistered);
	}

	@Transactional
	public void deleteIdea(User user, Long ideaId) {
		Idea idea = ideaRepository.findById(ideaId)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_NOT_FOUND));

		if (!idea.getUser().getUserId().equals(user.getUserId())) {
			throw CustomException.of(ErrorCode.ACCESS_DENIED);
		}

		ideaPurgeService.purgeIdea(idea);
	}

	/**
	 * 실패한 등록을 다시 시도한다 (FAILED → BLOCKCHAIN_PENDING, Outbox 재적재).
	 * 같은 commit 을 다시 보내므로 체인에 이미 있으면 게이트웨이/컨트랙트가 중복 등록을 막고,
	 * 웹훅·대사 배치가 체인 상태를 보고 REGISTERED 로 맞춘다.
	 */
	@Transactional
	public FileRegistrationResult retryRegistration(User user, Long ideaId, Long ideaFileId) {
		IdeaFile ideaFile = findOwnedFile(user, ideaId, ideaFileId);
		ideaFile.retryRegistration();
		blockchainOutboxService.enqueue(ideaFile);
		return FileRegistrationResult.of(ideaFile);
	}

	/**
	 * 서버 없이도 검증할 수 있는 증명 자료(proof.json).
	 * 파일 원본 + 이 자료만 있으면 tools/proof-verifier 로 해시·commit·서명·온체인 기록을 직접 확인할 수 있다.
	 */
	@Transactional(readOnly = true)
	public IdeaProofResponse getProof(User user, Long ideaId, Long ideaFileId) {
		IdeaFile ideaFile = findOwnedFile(user, ideaId, ideaFileId);
		if (ideaFile.getRegisterStatus() != IdeaFile.RegisterStatus.REGISTERED) {
			throw CustomException.of(ErrorCode.PROOF_NOT_AVAILABLE);
		}
		return IdeaProofResponse.of(
				ideaFile,
				BLOCKCHAIN_NETWORK,
				SEPOLIA_CHAIN_ID,
				contractAddress,
				serverSignatureService.getServerPublicKey()
		);
	}

	private IdeaFile findOwnedFile(User user, Long ideaId, Long ideaFileId) {
		Idea idea = ideaRepository.findById(ideaId)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_NOT_FOUND));
		if (!idea.getUser().getUserId().equals(user.getUserId())) {
			throw CustomException.of(ErrorCode.ACCESS_DENIED);
		}
		IdeaFile ideaFile = ideaFileRepository.findById(ideaFileId)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_FILE_NOT_FOUND));
		boolean owned = idea.getVersions().stream().anyMatch(v -> v.getFiles().contains(ideaFile));
		if (!owned) {
			throw CustomException.of(ErrorCode.IDEA_FILE_IDEA_MISMATCH);
		}
		return ideaFile;
	}

	@Transactional(readOnly = true)
	public IdeaCertificateResponse getCertificateData(User user, Long ideaId) {
		Idea idea = ideaRepository.findById(ideaId)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_NOT_FOUND));

		if (!idea.getUser().getUserId().equals(user.getUserId())) {
			throw CustomException.of(ErrorCode.ACCESS_DENIED);
		}

		IdeaVersion latestVersion = ideaVersionRepository
				.findTopByIdeaOrderByVersionNumberDesc(idea)
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_VERSION_NOT_FOUND));

		IdeaFile ideaFile = latestVersion.getFiles().stream()
				.findFirst()
				.orElseThrow(() -> CustomException.of(ErrorCode.IDEA_FILE_NOT_FOUND));

		if (ideaFile.getRegisterStatus() != IdeaFile.RegisterStatus.REGISTERED) {
			throw CustomException.of(ErrorCode.LATEST_VERSION_NOT_REGISTERED);
		}

		String ideaTitle = latestVersion.getTitle();

		return IdeaCertificateResponse.of(
				user,
				ideaFile,
				ideaTitle,
				BLOCKCHAIN_NETWORK,
				contractAddress
		);
	}

	private boolean hasNonEmptyFiles(List<MultipartFile> files) {
		if (files == null || files.isEmpty()) {
			return false;
		}
		return files.stream().anyMatch(file -> file != null && !file.isEmpty());
	}

	private void processImages(IdeaVersion ideaVersion, List<MultipartFile> images) throws IOException {
		if (images == null || images.isEmpty()) {
			return;
		}

		for (MultipartFile image : images) {
			if (!image.isEmpty()) {
				String extension = getFileExtension(image.getOriginalFilename());
				String fileName = UUID.randomUUID() + extension;
				String filePath = fileStorageService.uploadFile(image, fileName, "image");

				IdeaImage ideaImage = IdeaImage.of(
						image.getOriginalFilename(),
						fileName,
						filePath
				);

				ideaImage = ideaImageRepository.save(ideaImage);
				ideaVersion.addImage(ideaImage);
			}
		}
	}

	private List<FileHashInfo> processFiles(IdeaVersion ideaVersion, List<MultipartFile> files, Long requestTimestamp) throws IOException {
		List<FileHashInfo> fileHashInfoList = new ArrayList<>();

		if (files == null || files.isEmpty()) {
			return fileHashInfoList;
		}

		for (MultipartFile file : files) {
			if (!file.isEmpty()) {
				String extension = getFileExtension(file.getOriginalFilename());
				String fileName = UUID.randomUUID() + extension;
				String filePath = fileStorageService.uploadFile(file, fileName, "file");

				String fileHash = hashUtil.generateFileHash(file);
				String salt = hashUtil.generateSalt();
				String commit = hashUtil.generateCommit(fileHash, salt);

				IdeaFile ideaFile = IdeaFile.initialize(
						file.getOriginalFilename(),
						fileName,
						filePath,
						fileHash,
						salt,
						commit,
						requestTimestamp
				);

				ideaVersion.addFile(ideaFile);
				ideaFile = ideaFileRepository.save(ideaFile);

				fileHashInfoList.add(FileHashInfo.builder()
						.ideaFileId(ideaFile.getIdeaFileId())
						.fileName(file.getOriginalFilename())
						.fileHash(fileHash)
						.timestamp(requestTimestamp)
						.build());
			}
		}

		return fileHashInfoList;
	}

	private String normalizeSignature(String signature) {
		if (signature != null && !signature.startsWith("0x")) {
			return "0x" + signature;
		}
		return signature;
	}

	private String getFileExtension(String originalFilename) {
		if (originalFilename == null || !originalFilename.contains(".")) {
			return "";
		}
		return originalFilename.substring(originalFilename.lastIndexOf("."));
	}

	private void copyImagesFromPreviousVersion(IdeaVersion newVersion, IdeaVersion previousVersion, List<Long> deleteImageIds) {
		Set<IdeaImage> imagesToKeep = new HashSet<>(previousVersion.getImages());
		if (deleteImageIds != null && !deleteImageIds.isEmpty()) {
			imagesToKeep.removeIf(img -> deleteImageIds.contains(img.getIdeaImageId()));
		}
		for (IdeaImage image : imagesToKeep) {
			newVersion.addImage(image);
		}
	}

	private void copyFilesFromPreviousVersion(IdeaVersion newVersion, IdeaVersion previousVersion, List<Long> deleteFileIds) {
		Set<IdeaFile> filesToKeep = new HashSet<>(previousVersion.getFiles());
		if (deleteFileIds != null && !deleteFileIds.isEmpty()) {
			filesToKeep.removeIf(file -> deleteFileIds.contains(file.getIdeaFileId()));
		}
		for (IdeaFile file : filesToKeep) {
			newVersion.addFile(file);
		}
	}

	private IdeaFile getLatestFile(List<IdeaVersion> versions) {
		if (versions.isEmpty()) {
			return null;
		}

		IdeaVersion latestVersion = versions.getFirst();
		return latestVersion.getFiles().stream()
				.findFirst()
				.orElse(null);
	}

	private String getLatestCertificateUrl(IdeaFile latestFile) {
		return latestFile != null ? latestFile.getCertificateUrl() : null;
	}

	private Boolean checkLatestVersionRegistered(IdeaFile latestFile) {
		return latestFile != null && latestFile.getRegisterStatus() == IdeaFile.RegisterStatus.REGISTERED;
	}
}

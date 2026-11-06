package com.idear.backend.idea.domain;

import java.time.LocalDateTime;

import com.idear.backend.blockchain.domain.RegistrationFailureReason;
import com.idear.backend.global.exception.CustomException;
import com.idear.backend.global.exception.ErrorCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idea_files")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SQLDelete(sql = "UPDATE idea_files SET deleted_at = NOW() WHERE idea_file_id = ?")
@SQLRestriction("deleted_at IS NULL")
public class IdeaFile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long ideaFileId;

	@Column(nullable = false)
	private String originalFileName;

	@Column(nullable = false)
	private String fileName;

	@Column(nullable = false)
	private String filePath;

	// 파기(purge) 시 null 로 지워지므로 nullable
	@Column(length = 64)
	private String fileHash;

	@Column(length = 64)
	private String salt;

	@Column(length = 66, nullable = false, unique = true)
	private String commit;

	@Column(length = 255)
	private String userSignature;

	@Column(length = 255)
	private String serverSignature;

	/** 서명 당시 사용한 사용자 공개키 (키가 재발급돼도 과거 서명을 검증할 수 있도록 스냅샷 저장) */
	@Column(length = 500)
	private String signerPublicKey;

	/** 온체인 userSignature 필드에 실제로 기록한 값 (v2부터 SHA-256(사용자 서명)) */
	@Column(length = 66)
	private String onChainUserSignature;

	/** 증명 형식 버전. null/1 = 사용자 서명 원문을 체인에 기록(레거시), 2 = 서명 다이제스트 기록 */
	private Integer proofVersion;

	/** 마지막 상태 변경 시각 (대사 배치가 '오래 걸리는 건'을 찾는 기준) */
	private LocalDateTime statusChangedAt;

	/** 게이트웨이 등록 요청을 보낸 횟수 (대사 배치 재전송 포함) */
	@Builder.Default
	@Column(nullable = false)
	private Integer dispatchCount = 0;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Builder.Default
	private RegisterStatus registerStatus = RegisterStatus.PENDING_USER_SIGNATURE;

	@Column(length = 100)
	private String txHash;

	private Integer blockNumber;

	@Column(nullable = false)
	private Long requestedTimestamp;

	private Long registeredTimestamp;

	@Enumerated(EnumType.STRING)
	private RegistrationFailureReason registrationFailureReason;

	@Column(length = 500)
	private String certificateUrl;

	private LocalDateTime deletedAt;

	public enum RegisterStatus {
		PENDING_USER_SIGNATURE,
		BLOCKCHAIN_PENDING,
		REGISTERED,
		FAILED
	}

	public static IdeaFile initialize(
			String originalFileName,
			String fileName,
			String filePath,
			String fileHash,
			String salt,
			String commit,
			Long requestedTimestamp
	) {
		return IdeaFile.builder()
				.originalFileName(originalFileName)
				.fileName(fileName)
				.filePath(filePath)
				.fileHash(fileHash)
				.salt(salt)
				.commit(commit)
				.requestedTimestamp(requestedTimestamp)
				.build();
	}

	public void submitUserSignature(String userSignature, String serverSignature,
			String signerPublicKey, String onChainUserSignature, int proofVersion) {
		validateStatus(RegisterStatus.PENDING_USER_SIGNATURE);

		this.userSignature = userSignature;
		this.serverSignature = serverSignature;
		this.signerPublicKey = signerPublicKey;
		this.onChainUserSignature = onChainUserSignature;
		this.proofVersion = proofVersion;
		this.dispatchCount = 1;
		changeStatus(RegisterStatus.BLOCKCHAIN_PENDING);
	}

	/**
	 * 체인에서 직접 확인한 값으로 등록 완료 처리한다.
	 * FAILED 에서도 전이를 허용한다: "타임아웃으로 실패 처리했지만 실제로는 채굴된" 경우를 복구하기 위함.
	 *
	 * @return 상태가 실제로 바뀌었으면 true (이미 REGISTERED 면 false → 멱등)
	 */
	public boolean confirmOnChain(String txHash, Integer blockNumber, Long registeredAtMillis) {
		if (this.registerStatus == RegisterStatus.REGISTERED) {
			return false;
		}
		if (this.registerStatus != RegisterStatus.BLOCKCHAIN_PENDING && this.registerStatus != RegisterStatus.FAILED) {
			throw CustomException.of(ErrorCode.IDEA_FILE_STATUS_MISMATCH);
		}
		this.txHash = txHash;
		this.blockNumber = blockNumber;
		this.registeredTimestamp = registeredAtMillis;
		this.registrationFailureReason = null;
		changeStatus(RegisterStatus.REGISTERED);
		return true;
	}

	/**
	 * 실패 처리. BLOCKCHAIN_PENDING 일 때만 반영하고, 그 외(이미 성공/실패)는 무시한다.
	 *
	 * @return 상태가 실제로 바뀌었으면 true
	 */
	public boolean markFailed(String txHash, RegistrationFailureReason reason) {
		if (this.registerStatus != RegisterStatus.BLOCKCHAIN_PENDING) {
			return false;
		}
		this.txHash = txHash;
		this.registrationFailureReason = reason;
		changeStatus(RegisterStatus.FAILED);
		return true;
	}

	/** 사용자/운영자의 수동 재시도: FAILED → BLOCKCHAIN_PENDING */
	public void retryRegistration() {
		if (this.registerStatus != RegisterStatus.FAILED
				|| this.registrationFailureReason == RegistrationFailureReason.ALREADY_REGISTERED) {
			throw CustomException.of(ErrorCode.REGISTRATION_NOT_RETRYABLE);
		}
		this.registrationFailureReason = null;
		this.txHash = null;
		this.dispatchCount = this.dispatchCount + 1;
		changeStatus(RegisterStatus.BLOCKCHAIN_PENDING);
	}

	/** 대사 배치가 '체인에 없고 오래 펜딩'인 건을 다시 보낼 때 */
	public void markRedispatched() {
		validateStatus(RegisterStatus.BLOCKCHAIN_PENDING);
		this.dispatchCount = this.dispatchCount + 1;
		this.statusChangedAt = LocalDateTime.now();
	}

	public void setCertificateUrl(String certificateUrl) {
		this.certificateUrl = certificateUrl;
	}

	/**
	 * 개인정보 파기: 온체인 commit 을 재계산·연결하는 데 필요한 값(fileHash, salt, 서명, 공개키)을 지운다.
	 * commit 자체는 체인에 이미 공개된 값이므로 중복 등록 방지를 위해 남긴다.
	 */
	public void purge() {
		this.fileHash = null;
		this.salt = null;
		this.userSignature = null;
		this.serverSignature = null;
		this.signerPublicKey = null;
		this.certificateUrl = null;
		this.deletedAt = LocalDateTime.now();
	}

	public int effectiveProofVersion() {
		return proofVersion == null ? 1 : proofVersion;
	}

	private void changeStatus(RegisterStatus next) {
		this.registerStatus = next;
		this.statusChangedAt = LocalDateTime.now();
	}

	private void validateStatus(RegisterStatus expected) {
		if (this.registerStatus != expected) {
			throw CustomException.of(ErrorCode.IDEA_FILE_STATUS_MISMATCH);
		}
	}
}
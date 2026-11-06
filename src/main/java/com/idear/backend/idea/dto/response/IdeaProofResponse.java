package com.idear.backend.idea.dto.response;

import com.idear.backend.blockchain.crypto.ProofPayloads;
import com.idear.backend.idea.domain.IdeaFile;

import lombok.Builder;
import lombok.Getter;

/**
 * proof.json — 서버 없이 검증하기 위한 증명 자료.
 *
 * <p>검증 절차 (tools/proof-verifier/index.html 이 자동 수행)
 * <ol>
 *   <li>SHA-256(파일) == fileHash</li>
 *   <li>"0x" + SHA-256(utf8(fileHash + salt)) == commit</li>
 *   <li>userPublicKey 로 userSignedMessage 에 대한 userSignature 검증</li>
 *   <li>serverPublicKey 로 serverSignedMessage 에 대한 serverSignature 검증</li>
 *   <li>SHA-256(userSignature) == onChainUserSignature (v2)</li>
 *   <li>컨트랙트 getCommit(commit) 결과의 timestamp·userSignature·serverSignature 가 일치</li>
 * </ol>
 * salt 와 서명 원문이 들어 있으므로 소유자 본인만 받을 수 있고, 외부에 공유하면 파일과 사용자가 연결될 수 있다.
 */
@Getter
@Builder
public class IdeaProofResponse {

	private String format;
	private Integer proofVersion;

	// 체인
	private String network;
	private Long chainId;
	private String contractAddress;
	private String txHash;
	private Integer blockNumber;
	private Long registeredAt;

	// 파일·commit
	private Long ideaFileId;
	private String originalFileName;
	private String fileHash;
	private String salt;
	private String commit;
	private String commitRule;
	private Long requestedTimestamp;

	// 사용자 서명
	private String userPublicKey;
	private String userSignedMessage;
	private String userSignature;
	private String userSignatureRule;
	private String onChainUserSignature;
	private String onChainUserSignatureRule;

	// 서버 서명
	private String serverPublicKey;
	private String serverSignedMessage;
	private String serverSignature;
	private String serverSignatureRule;

	public static IdeaProofResponse of(IdeaFile file, String network, Long chainId,
			String contractAddress, String serverPublicKey) {
		boolean v2 = file.effectiveProofVersion() >= 2;
		return IdeaProofResponse.builder()
				.format(ProofPayloads.PROOF_FORMAT)
				.proofVersion(file.effectiveProofVersion())
				.network(network)
				.chainId(chainId)
				.contractAddress(contractAddress)
				.txHash(file.getTxHash())
				.blockNumber(file.getBlockNumber())
				.registeredAt(file.getRegisteredTimestamp() == null ? null : file.getRegisteredTimestamp() / 1000)
				.ideaFileId(file.getIdeaFileId())
				.originalFileName(file.getOriginalFileName())
				.fileHash(file.getFileHash())
				.salt(file.getSalt())
				.commit(file.getCommit())
				.commitRule("commit = '0x' + hex(SHA-256(utf8(fileHash + salt)))")
				.requestedTimestamp(file.getRequestedTimestamp())
				.userPublicKey(file.getSignerPublicKey())
				.userSignedMessage(ProofPayloads.userMessage(file.getFileHash(), file.getRequestedTimestamp()))
				.userSignature(file.getUserSignature())
				.userSignatureRule("ECDSA P-256 / SHA-256 (WebCrypto), signature = IEEE P1363 r||s, publicKey = SPKI base64")
				.onChainUserSignature(v2 ? file.getOnChainUserSignature() : file.getUserSignature())
				.onChainUserSignatureRule(v2 ? "onChainUserSignature = '0x' + hex(SHA-256(userSignature bytes))"
						: "legacy(v1): userSignature stored on-chain as-is")
				.serverPublicKey(serverPublicKey)
				.serverSignedMessage(ProofPayloads.serverMessage(file.getCommit(), file.getRequestedTimestamp(), file.getUserSignature()))
				.serverSignature(file.getServerSignature())
				.serverSignatureRule("ECDSA P-256, DER. signed bytes = SHA-256(utf8(serverSignedMessage)) "
						+ "then SHA256withECDSA (message is hashed twice), publicKey = uncompressed 0x04||X||Y")
				.build();
	}
}

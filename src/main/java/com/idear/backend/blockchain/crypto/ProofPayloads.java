package com.idear.backend.blockchain.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 서명·온체인 기록에 쓰이는 메시지 형식을 한곳에서 정의한다.
 * 프론트엔드, 서버, 독립 검증 도구(tools/proof-verifier)가 모두 같은 규칙을 따라야 하므로
 * 문자열 조합 규칙을 흩어 두지 않는다.
 */
public final class ProofPayloads {

	public static final String PROOF_FORMAT = "IDEAR_PROOF_V2";

	private ProofPayloads() {
	}

	/** 사용자 서명 메시지 (프론트엔드 signIdeaFiles.ts 와 동일): fileHash|requestedTimestamp */
	public static String userMessage(String fileHash, Long requestedTimestamp) {
		return fileHash + "|" + requestedTimestamp;
	}

	/** 서버 서명 메시지: commit|requestedTimestamp|userSignature */
	public static String serverMessage(String commit, Long requestedTimestamp, String userSignature) {
		return String.join("|", commit, requestedTimestamp.toString(), userSignature);
	}

	/**
	 * 온체인에는 사용자 서명 원문 대신 SHA-256(서명 바이트)만 기록한다.
	 *
	 * <p>이유: 사용자 서명은 fileHash에 대한 서명이므로, 원문이 체인에 공개되면
	 * "원본 파일 + 사용자 공개키"만으로 salt 없이도 사용자와 파일을 연결할 수 있다.
	 * 해시만 올리면 온체인 값으로는 검증·연결이 불가능하고,
	 * proof.json을 가진 소유자만 원문 서명을 제시해 일치 여부를 증명할 수 있다.
	 */
	public static String onChainUserSignatureDigest(String userSignatureHex) {
		String hex = userSignatureHex.startsWith("0x") ? userSignatureHex.substring(2) : userSignatureHex;
		return "0x" + sha256Hex(HexFormat.of().parseHex(hex));
	}

	public static String sha256Hex(byte[] input) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String sha256Hex(String input) {
		return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
	}
}

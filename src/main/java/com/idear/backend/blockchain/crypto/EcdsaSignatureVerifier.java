package com.idear.backend.blockchain.crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * 사용자(브라우저 WebCrypto)가 만든 ECDSA P-256 서명을 서버에서 검증한다.
 *
 * <p>호환성 포인트
 * <ul>
 *   <li>공개키: WebCrypto exportKey('spki') 결과를 Base64 인코딩한 값 (X.509 SubjectPublicKeyInfo)</li>
 *   <li>서명: WebCrypto는 IEEE P1363 형식(r||s, 64바이트)을 만들고 Java는 ASN.1 DER을 기대한다.
 *       따라서 64바이트 서명은 DER로 변환한 뒤 검증한다. (DER 서명도 그대로 허용)</li>
 *   <li>해시: WebCrypto {name:'ECDSA', hash:'SHA-256'} 은 메시지를 한 번 SHA-256 한 뒤 서명한다.
 *       Java SHA256withECDSA 와 같다.</li>
 * </ul>
 */
@Component
public class EcdsaSignatureVerifier {

	private static final String CURVE_FIELD_SIZE_HINT = "P-256";
	private static final int P256_COORDINATE_BYTES = 32;

	/**
	 * SPKI(Base64) 공개키를 파싱하고 P-256 키인지 확인한다. 잘못된 키 등록을 막는 용도.
	 */
	public ECPublicKey parsePublicKey(String spkiBase64) {
		try {
			byte[] der = Base64.getDecoder().decode(spkiBase64.trim());
			PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
			if (!(key instanceof ECPublicKey ecKey)) {
				throw new IllegalArgumentException("EC public key required");
			}
			int fieldSize = ecKey.getParams().getCurve().getField().getFieldSize();
			if (fieldSize != 256) {
				throw new IllegalArgumentException(CURVE_FIELD_SIZE_HINT + " public key required");
			}
			return ecKey;
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid SPKI public key", e);
		}
	}

	/**
	 * @param spkiBase64   사용자 공개키 (SPKI, Base64)
	 * @param message      서명 대상 문자열 (UTF-8)
	 * @param signatureHex 0x 접두사 유무 무관, raw(r||s) 또는 DER
	 */
	public boolean verify(String spkiBase64, String message, String signatureHex) {
		if (spkiBase64 == null || message == null || signatureHex == null) {
			return false;
		}
		try {
			ECPublicKey publicKey = parsePublicKey(spkiBase64);
			byte[] signature = HexFormat.of().parseHex(strip0x(signatureHex));
			byte[] der = signature.length == P256_COORDINATE_BYTES * 2 ? rawToDer(signature) : signature;

			Signature verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(publicKey);
			verifier.update(message.getBytes(StandardCharsets.UTF_8));
			return verifier.verify(der);
		} catch (Exception e) {
			// 형식 오류(잘못된 hex, 깨진 DER 등)는 모두 "검증 실패"로 취급
			return false;
		}
	}

	/** IEEE P1363 (r||s) → ASN.1 DER SEQUENCE { INTEGER r, INTEGER s } */
	public static byte[] rawToDer(byte[] raw) {
		int half = raw.length / 2;
		byte[] r = toDerInteger(new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, half)));
		byte[] s = toDerInteger(new BigInteger(1, java.util.Arrays.copyOfRange(raw, half, raw.length)));

		ByteArrayOutputStream seq = new ByteArrayOutputStream();
		seq.write(0x30);
		writeLength(seq, r.length + s.length);
		seq.writeBytes(r);
		seq.writeBytes(s);
		return seq.toByteArray();
	}

	/** ASN.1 DER → IEEE P1363 (r||s), 각 32바이트 고정 */
	public static byte[] derToRaw(byte[] der) {
		int offset = 2;
		if ((der[1] & 0x80) != 0) {
			offset = 2 + (der[1] & 0x7f);
		}
		int rLen = der[offset + 1];
		BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(der, offset + 2, offset + 2 + rLen));
		int sOffset = offset + 2 + rLen;
		int sLen = der[sOffset + 1];
		BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(der, sOffset + 2, sOffset + 2 + sLen));

		byte[] out = new byte[P256_COORDINATE_BYTES * 2];
		copyUnsigned(r, out, 0);
		copyUnsigned(s, out, P256_COORDINATE_BYTES);
		return out;
	}

	private static void copyUnsigned(BigInteger value, byte[] out, int offset) {
		byte[] bytes = value.toByteArray();
		int start = bytes.length > P256_COORDINATE_BYTES ? bytes.length - P256_COORDINATE_BYTES : 0;
		int len = bytes.length - start;
		System.arraycopy(bytes, start, out, offset + P256_COORDINATE_BYTES - len, len);
	}

	private static byte[] toDerInteger(BigInteger value) {
		byte[] bytes = value.toByteArray(); // 부호 비트 때문에 필요한 경우 앞에 0x00이 붙는다
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0x02);
		writeLength(out, bytes.length);
		out.writeBytes(bytes);
		return out.toByteArray();
	}

	private static void writeLength(ByteArrayOutputStream out, int length) {
		if (length < 0x80) {
			out.write(length);
		} else {
			out.write(0x81);
			out.write(length);
		}
	}

	private static String strip0x(String hex) {
		return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
	}
}

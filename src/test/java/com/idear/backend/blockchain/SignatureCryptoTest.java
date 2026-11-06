package com.idear.backend.blockchain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.idear.backend.blockchain.crypto.EcdsaSignatureVerifier;
import com.idear.backend.blockchain.crypto.ProofPayloads;
import com.idear.backend.idea.util.ServerSignatureService;

class SignatureCryptoTest {

	private final EcdsaSignatureVerifier verifier = new EcdsaSignatureVerifier();

	@Test
	@DisplayName("WebCrypto 형식(raw r||s) 사용자 서명을 SPKI 공개키로 검증한다")
	void verifyWebCryptoSignature() throws Exception {
		KeyPair kp = CryptoTestSupport.newP256KeyPair();
		String message = ProofPayloads.userMessage("ab".repeat(32), 1730000000000L);
		String sig = CryptoTestSupport.webCryptoSign(kp, message);

		assertThat(HexFormat.of().parseHex(sig.substring(2))).hasSize(64);
		assertThat(verifier.verify(CryptoTestSupport.spkiBase64(kp), message, sig)).isTrue();
		// 0x 없이 와도 동일
		assertThat(verifier.verify(CryptoTestSupport.spkiBase64(kp), message, sig.substring(2))).isTrue();
	}

	@Test
	@DisplayName("메시지·서명·키가 하나라도 다르면 검증에 실패한다")
	void rejectTampered() throws Exception {
		KeyPair kp = CryptoTestSupport.newP256KeyPair();
		KeyPair other = CryptoTestSupport.newP256KeyPair();
		String message = "hash|1";
		String sig = CryptoTestSupport.webCryptoSign(kp, message);

		assertThat(verifier.verify(CryptoTestSupport.spkiBase64(kp), "hash|2", sig)).isFalse();
		assertThat(verifier.verify(CryptoTestSupport.spkiBase64(other), message, sig)).isFalse();
		String flipped = sig.substring(0, sig.length() - 1) + (sig.endsWith("0") ? "1" : "0");
		assertThat(verifier.verify(CryptoTestSupport.spkiBase64(kp), message, flipped)).isFalse();
		assertThat(verifier.verify(CryptoTestSupport.spkiBase64(kp), message, "0xnothex")).isFalse();
		assertThat(verifier.verify(CryptoTestSupport.spkiBase64(kp), message, "0x1234")).isFalse();
	}

	@Test
	@DisplayName("P-256 SPKI 가 아닌 공개키는 등록 단계에서 거부한다")
	void rejectInvalidPublicKey() {
		assertThatThrownBy(() -> verifier.parsePublicKey("not-base64!!")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> verifier.parsePublicKey("AAAA")).isInstanceOf(IllegalArgumentException.class);
	}

	@RepeatedTest(20)
	@DisplayName("raw <-> DER 변환은 서로 역변환이다 (r,s 앞자리 0 포함 케이스 반복)")
	void rawDerRoundTrip() throws Exception {
		KeyPair kp = CryptoTestSupport.newP256KeyPair();
		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(kp.getPrivate());
		signer.update("x".getBytes());
		byte[] der = signer.sign();
		byte[] raw = EcdsaSignatureVerifier.derToRaw(der);
		assertThat(raw).hasSize(64);
		assertThat(EcdsaSignatureVerifier.rawToDer(raw)).isEqualTo(der);
	}

	@RepeatedTest(10)
	@DisplayName("서버 공개키 직접 계산(스칼라 곱) 결과가 JCA 가 만든 공개키와 같고, 서버 서명이 그 키로 검증된다")
	void serverKeyDerivationAndSignature() throws Exception {
		KeyPair kp = CryptoTestSupport.newP256KeyPair();
		BigInteger s = ((ECPrivateKey) kp.getPrivate()).getS();
		ECPoint expected = ((ECPublicKey) kp.getPublic()).getW();

		ServerSignatureService service = new ServerSignatureService();
		ReflectionTestUtils.setField(service, "serverPrivateKey", "0x" + s.toString(16));
		service.init();

		byte[] pub = HexFormat.of().parseHex(service.getServerPublicKey().substring(2));
		assertThat(pub).hasSize(65);
		assertThat(pub[0]).isEqualTo((byte) 0x04);
		assertThat(new BigInteger(1, java.util.Arrays.copyOfRange(pub, 1, 33))).isEqualTo(expected.getAffineX());
		assertThat(new BigInteger(1, java.util.Arrays.copyOfRange(pub, 33, 65))).isEqualTo(expected.getAffineY());

		// 서버 서명 규칙: SHA-256(message) 를 다시 SHA256withECDSA → "두 번 해시"
		String message = ProofPayloads.serverMessage("0xabc", 1L, "0xdef");
		String sig = service.generateServerSignature("0xabc", 1L, "0xdef");
		byte[] once = MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8));

		Signature v = Signature.getInstance("SHA256withECDSA");
		v.initVerify(publicKeyFrom(pub));
		v.update(once);
		assertThat(v.verify(HexFormat.of().parseHex(sig.substring(2)))).isTrue();
	}

	@Test
	@DisplayName("온체인 사용자 서명 다이제스트는 서명 바이트의 SHA-256 이며 원문 서명과 다르다")
	void onChainDigest() throws Exception {
		KeyPair kp = CryptoTestSupport.newP256KeyPair();
		String sig = CryptoTestSupport.webCryptoSign(kp, "m");
		String digest = ProofPayloads.onChainUserSignatureDigest(sig);
		assertThat(digest).startsWith("0x").hasSize(66).isNotEqualToIgnoringCase(sig);
		assertThat(digest.substring(2)).isEqualTo(ProofPayloads.sha256Hex(HexFormat.of().parseHex(sig.substring(2))));
	}

	private static PublicKey publicKeyFrom(byte[] uncompressed) throws Exception {
		AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
		params.init(new ECGenParameterSpec("secp256r1"));
		ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
		ECPoint w = new ECPoint(new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 1, 33)),
				new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 33, 65)));
		return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, spec));
	}
}

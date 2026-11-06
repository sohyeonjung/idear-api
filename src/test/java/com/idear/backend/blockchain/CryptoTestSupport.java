package com.idear.backend.blockchain;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;

import com.idear.backend.blockchain.crypto.EcdsaSignatureVerifier;

/** 브라우저 WebCrypto 동작을 Java 로 재현하는 테스트 도우미 */
public final class CryptoTestSupport {

	private CryptoTestSupport() {
	}

	public static KeyPair newP256KeyPair() throws Exception {
		KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
		gen.initialize(new ECGenParameterSpec("secp256r1"));
		return gen.generateKeyPair();
	}

	/** WebCrypto exportKey('spki') + base64 와 같은 값 */
	public static String spkiBase64(KeyPair keyPair) {
		return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
	}

	/** WebCrypto sign({name:'ECDSA', hash:'SHA-256'}) 결과(r||s 64바이트)를 0x hex 로 */
	public static String webCryptoSign(KeyPair keyPair, String message) throws Exception {
		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(keyPair.getPrivate());
		signer.update(message.getBytes(StandardCharsets.UTF_8));
		byte[] raw = EcdsaSignatureVerifier.derToRaw(signer.sign());
		return "0x" + HexFormat.of().formatHex(raw);
	}
}

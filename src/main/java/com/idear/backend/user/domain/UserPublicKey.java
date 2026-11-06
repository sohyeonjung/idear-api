package com.idear.backend.user.domain;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 공개키 이력.
 *
 * <p>기존에는 User.publicKey 하나를 덮어썼기 때문에, 브라우저 데이터 삭제나 다른 기기 로그인으로
 * 키가 재발급되면 과거 서명을 검증할 공개키가 사라졌다. 키를 폐기(revoke)만 하고 이력은 남겨
 * 과거 등록 건도 계속 검증할 수 있게 한다.
 */
@Entity
@Table(name = "user_public_keys", indexes = @Index(name = "idx_user_public_keys_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPublicKey {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long userPublicKeyId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(length = 500, nullable = false)
	private String publicKey;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private LocalDateTime revokedAt;

	public static UserPublicKey of(User user, String publicKey) {
		UserPublicKey key = new UserPublicKey();
		key.user = user;
		key.publicKey = publicKey;
		key.createdAt = LocalDateTime.now();
		return key;
	}

	public void revoke() {
		if (this.revokedAt == null) {
			this.revokedAt = LocalDateTime.now();
		}
	}

	public boolean isActive() {
		return revokedAt == null;
	}
}

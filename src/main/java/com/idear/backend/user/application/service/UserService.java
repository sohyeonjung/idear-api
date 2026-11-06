package com.idear.backend.user.application.service;

import com.idear.backend.blockchain.crypto.EcdsaSignatureVerifier;
import com.idear.backend.email.service.EmailService;
import com.idear.backend.idea.application.IdeaPurgeService;
import com.idear.backend.user.domain.UserPublicKey;
import com.idear.backend.user.infrastructure.repository.UserPublicKeyRepository;
import com.idear.backend.global.config.UserProperties;
import com.idear.backend.global.exception.CustomException;
import com.idear.backend.global.exception.ErrorCode;
import com.idear.backend.idea.application.FileStorageService;
import com.idear.backend.user.domain.User;
import com.idear.backend.user.dto.response.UserInfoResponse;
import com.idear.backend.user.infrastructure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final FileStorageService fileStorageService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserProperties userProperties;
    private final UserPublicKeyRepository userPublicKeyRepository;
    private final EcdsaSignatureVerifier ecdsaSignatureVerifier;
    private final IdeaPurgeService ideaPurgeService;
    private final SecureRandom secureRandom = new SecureRandom();

    private static final String EMAIL_VERIFICATION_PREFIX = "email:verification:";
    private static final String EMAIL_VERIFIED_PREFIX = "email:verified:";

    @Transactional(readOnly = true)
    public UserInfoResponse getUserInfo(User user) {
        return UserInfoResponse.from(user, userProperties.getProfile().getDefaultImageUrl());
    }

    @Transactional
    public void updateName(User user, String name) {
        user.updateUsername(name);
    }

    /**
     * 공개키 등록. 기존 키는 덮어쓰지 않고 폐기(revoke) 처리해 이력으로 남긴다.
     * 과거에 등록된 파일은 IdeaFile.signerPublicKey 에 서명 당시 키가 저장되어 있어 계속 검증 가능하다.
     */
    @Transactional
    public void registerPublicKey(User user, String publicKey) {
        try {
            ecdsaSignatureVerifier.parsePublicKey(publicKey);
        } catch (IllegalArgumentException e) {
            throw CustomException.of(ErrorCode.INVALID_PUBLIC_KEY);
        }

        userPublicKeyRepository.findAllByUserAndRevokedAtIsNull(user)
                .forEach(UserPublicKey::revoke);
        userPublicKeyRepository.save(UserPublicKey.of(user, publicKey));
        user.registerPublicKey(publicKey);
    }

    @Transactional
    public void deleteUser(User user) {
        // 탈퇴 시 사용자의 아이디어 파일 증빙 데이터(salt·서명·원본)를 파기해
        // 온체인 commit과 정보주체 간 연결 가능성을 최소화한다.
        ideaPurgeService.purgeAllIdeasOf(user);
        userPublicKeyRepository.findAllByUserAndRevokedAtIsNull(user)
                .forEach(UserPublicKey::revoke);
        user.deleteUser();
    }

    @Transactional
    public void updateNotificationSettings(User user, Boolean pushEnabled, Boolean emailEnabled) {
        user.updateNotificationSettings(pushEnabled, emailEnabled);
    }

    // 이메일 인증 메서드
    public void sendEmailVerificationCode(String email) {
        // 이메일 중복 확인
        if (userRepository.existsByEmail(email)) {
            throw CustomException.of(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 6자리 인증 코드 생성
        String code = generateVerificationCode();

        // Redis에 설정된 만료 시간으로 저장
        String key = EMAIL_VERIFICATION_PREFIX + email;
        long expiration = userProperties.getEmail().getVerification().getCodeExpirationMinutes();
        redisTemplate.opsForValue().set(key, code, expiration, TimeUnit.MINUTES);

        // 이메일 전송
        emailService.sendEmail(email, "[iDear] 이메일 인증 코드",
                String.format("인증 코드: %s\n\n이 코드는 %d분간 유효합니다.", code, expiration));
    }

    public void verifyEmailCode(String email, String code) {
        String key = EMAIL_VERIFICATION_PREFIX + email;
        String storedCode = (String) redisTemplate.opsForValue().get(key);

        if (storedCode == null) {
            throw CustomException.of(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }

        if (!storedCode.equals(code)) {
            throw CustomException.of(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        // 인증 완료 표시
        String verifiedKey = EMAIL_VERIFIED_PREFIX + email;
        long expiration = userProperties.getEmail().getVerification().getVerifiedExpirationMinutes();
        redisTemplate.opsForValue().set(verifiedKey, "true", expiration, TimeUnit.MINUTES);

        // 인증 코드 삭제
        redisTemplate.delete(key);
    }

    @Transactional
    public void updateEmail(User user, String email) {
        // 이메일 인증 확인
        String verifiedKey = EMAIL_VERIFIED_PREFIX + email;
        String verified = (String) redisTemplate.opsForValue().get(verifiedKey);

        if (verified == null || !verified.equals("true")) {
            throw CustomException.of(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        // 이메일 중복 재확인
        if (userRepository.existsByEmail(email)) {
            throw CustomException.of(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 이메일 업데이트
        user.updateEmail(email);

        // Redis 데이터 정리
        redisTemplate.delete(verifiedKey);
    }

    // 프로필 이미지 메서드
    @Transactional
    public String uploadProfileImage(User user, MultipartFile file) {
        // 파일 유효성 검사
        validateImageFile(file);

        // 고유한 파일명 생성
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String fileName = UUID.randomUUID().toString() + "." + extension;

        // 기존 프로필 이미지 삭제 (존재하는 경우)
        if (user.getProfileImageUrl() != null) {
            deleteOldProfileImage(user.getProfileImageUrl());
        }

        // S3에 업로드
        try {
            String profileImageUrl = fileStorageService.uploadFile(file, fileName, "profile-images");

            // 사용자 엔티티 업데이트
            user.updateProfileImage(profileImageUrl);

            return profileImageUrl;
        } catch (IOException e) {
            throw CustomException.of(ErrorCode.FILE_UPLOAD_ERROR);
        }
    }

    // 헬퍼 메서드
    private String generateVerificationCode() {
        return String.format("%06d", secureRandom.nextInt(1000000));
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw CustomException.of(ErrorCode.INVALID_IMAGE_FILE);
        }

        // 파일 크기 확인
        if (file.getSize() > userProperties.getProfile().getMaxImageSize()) {
            throw CustomException.of(ErrorCode.IMAGE_FILE_TOO_LARGE);
        }

        // 파일 확장자 확인
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw CustomException.of(ErrorCode.INVALID_IMAGE_FILE);
        }

        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!userProperties.getProfile().getAllowedExtensions().contains(extension)) {
            throw CustomException.of(ErrorCode.INVALID_IMAGE_FILE);
        }

        // 콘텐츠 타입 확인
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw CustomException.of(ErrorCode.INVALID_IMAGE_FILE);
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    private void deleteOldProfileImage(String profileImageUrl) {
        try {
            // URL에서 파일명 추출
            String[] urlParts = profileImageUrl.split("/");
            String fileName = urlParts[urlParts.length - 1];
            fileStorageService.deleteFile(fileName, "profile-images");
        } catch (Exception e) {
            // 에러 로그를 남기고 작업은 실패시키지 않음
            log.error("Failed to delete old profile image: {}", profileImageUrl, e);
        }
    }
}

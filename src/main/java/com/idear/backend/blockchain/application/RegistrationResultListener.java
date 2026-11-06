package com.idear.backend.blockchain.application;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.idear.backend.alert.application.service.AlertService;
import com.idear.backend.certificate.application.CertificateService;
import com.idear.backend.email.service.EmailService;
import com.idear.backend.idea.domain.Idea;
import com.idear.backend.idea.domain.IdeaFile;
import com.idear.backend.idea.infrastructure.repository.IdeaFileRepository;
import com.idear.backend.idea.infrastructure.repository.IdeaRepository;
import com.idear.backend.user.domain.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 등록 결과 후처리 (증명서 PDF, 알림, 이메일).
 *
 * <p>기존에는 상태 변경과 같은 트랜잭션에서 PDF 생성·S3 업로드·메일 발송까지 했다.
 * 그러면 트랜잭션이 길어지고, 부가 작업 하나가 실패해도 "등록 완료"라는 핵심 상태까지 롤백될 수 있다.
 * 상태 변경이 커밋된 뒤(AFTER_COMMIT) 별도 트랜잭션에서 실행하고, 실패해도 핵심 상태는 유지된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationResultListener {

	private static final String ALERT_MESSAGE_SUCCESS = "아이디어 파일이 등록되었습니다. 내용이 맞는지 확인해주세요.";
	private static final String ALERT_MESSAGE_FAILURE = "아이디어 파일 등록이 실패하였습니다. 상세 내역을 확인해주세요.";
	private static final String REGISTRATION_STATUS_SUCCESS = "성공";
	private static final String REGISTRATION_STATUS_FAILURE = "실패";
	private static final String EMAIL_SUBJECT = "iDear - 블록체인 등록 결과";

	private final IdeaFileRepository ideaFileRepository;
	private final IdeaRepository ideaRepository;
	private final CertificateService certificateService;
	private final AlertService alertService;
	private final EmailService emailService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onRegistrationResult(RegistrationResultEvent event) {
		Optional<IdeaFile> fileOpt = ideaFileRepository.findById(event.ideaFileId());
		if (fileOpt.isEmpty()) {
			return;
		}
		IdeaFile ideaFile = fileOpt.get();
		Optional<Idea> ideaOpt = ideaRepository.findIdeaByFile(ideaFile);
		if (ideaOpt.isEmpty()) {
			// 등록 결과가 오기 전에 아이디어가 삭제된 경우: 상태만 남기고 알림은 보내지 않는다.
			return;
		}
		Idea idea = ideaOpt.get();
		User user = idea.getUser();

		if (event.success()) {
			try {
				String certificateUrl = certificateService.generateAndUploadCertificate(ideaFile, user, idea);
				ideaFile.setCertificateUrl(certificateUrl);
			} catch (Exception e) {
				log.error("Certificate generation failed for fileId={}", ideaFile.getIdeaFileId(), e);
			}
		}

		String alertMessage = event.success() ? ALERT_MESSAGE_SUCCESS : ALERT_MESSAGE_FAILURE;
		if (Boolean.TRUE.equals(user.getPushEnabled())) {
			alertService.createRegistrationAlert(alertMessage, user, idea.getIdeaId(), ideaFile);
		}

		if (Boolean.TRUE.equals(user.getEmailEnabled())) {
			try {
				Map<String, Object> variables = new HashMap<>();
				variables.put("userName", user.getName());
				variables.put("registrationStatus", event.success() ? REGISTRATION_STATUS_SUCCESS : REGISTRATION_STATUS_FAILURE);
				if (event.failureReason() != null) {
					variables.put("failureReason", event.failureReason().toString());
				}
				emailService.sendEmailWithTemplate(user.getEmail(), EMAIL_SUBJECT, "blockchain-registration", variables);
			} catch (Exception e) {
				log.error("Registration email failed for fileId={}", ideaFile.getIdeaFileId(), e);
			}
		}
	}
}

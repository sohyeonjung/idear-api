package com.idear.backend.idea.controller;

import com.idear.backend.global.ApiResponse;
import com.idear.backend.global.annotation.ValidatedUser;
import com.idear.backend.idea.application.IdeaService;
import com.idear.backend.idea.dto.request.FileSignatureRequest;
import com.idear.backend.idea.dto.request.IdeaCreateRequest;
import com.idear.backend.idea.dto.request.IdeaUpdateRequest;
import com.idear.backend.idea.dto.response.*;
import com.idear.backend.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Tag(name = "Idea", description = "아이디어 등록 및 관리 API")
@RestController
@RequestMapping("/ideas")
@RequiredArgsConstructor
public class IdeaController {

	private final IdeaService ideaService;

	@Operation(
		summary = "아이디어 초기 등록",
		description = "아이디어 및 파일 초기 등록 요청. 파일은 S3에 업로드되고 각 파일의 해시값과 타임스탬프를 반환합니다. (등록 1단계)"
	)
	@PostMapping(consumes = {"multipart/form-data"})
	public ResponseEntity<ApiResponse<IdeaRegistrationInitResponse>> initIdeaRegistration(
		@Parameter(hidden = true) @ValidatedUser User user,
		@Parameter(description = "아이디어 등록 정보", required = true)
		@Valid @RequestPart(value = "ideaData") IdeaCreateRequest ideaCreateRequest,
		@Parameter(description = "아이디어 설명용 이미지 파일 목록")
		@RequestPart(value = "images", required = false) List<MultipartFile> images,
		@Parameter(description = "블록체인 등록 대상 파일 목록 (최대 20MB)")
		@RequestPart(value = "files", required = false) List<MultipartFile> files
	) throws IOException {
		IdeaRegistrationInitResponse response = ideaService.initIdeaRegistration(user, ideaCreateRequest, images, files);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(
		summary = "파일 서명 제출 및 블록체인 등록",
		description = "클라이언트에서 생성한 파일별 서명을 제출하고, 블록체인에 commit을 등록합니다. (등록 2단계)"
	)
	@PostMapping("/{ideaId}/signatures")
	public ResponseEntity<ApiResponse<IdeaRegistrationCompleteResponse>> submitSignatures(
		@Parameter(hidden = true) @ValidatedUser User user,
		@Parameter(description = "아이디어 ID", required = true, example = "1")
		@PathVariable("ideaId") Long ideaId,
		@Valid @RequestBody FileSignatureRequest request
	) {
		IdeaRegistrationCompleteResponse response = ideaService.submitSignatures(user, ideaId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(
			summary = "아이디어 수정",
			description = "아이디어의 파일 및 메타데이터를 수정합니다. 파일 변경 시 새 버전 생성, 메타만 변경 시 최신 버전 수정."
	)
	@PatchMapping(value = "/{ideaId}", consumes = {"multipart/form-data"})
	public ResponseEntity<ApiResponse<IdeaUpdateResponse>> updateIdea(
			@Parameter(hidden = true) @ValidatedUser User user,
			@Parameter(description = "아이디어 ID", required = true, example = "1")
			@PathVariable("ideaId") Long ideaId,
			@Parameter(description = "수정 정보 (삭제 대상 파일/이미지 ID, 메타데이터)")
			@RequestPart(value = "ideaData", required = false) IdeaUpdateRequest ideaUpdateRequest,
			@Parameter(description = "추가할 이미지 목록")
			@RequestPart(value = "images", required = false) List<MultipartFile> images,
			@Parameter(description = "추가할 파일 목록")
			@RequestPart(value = "files", required = false) List<MultipartFile> files
	) throws IOException {
		IdeaUpdateResponse response = ideaService.updateIdea(user, ideaId, ideaUpdateRequest, images, files);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(
		summary = "내 아이디어 목록 조회",
		description = "현재 로그인한 사용자가 등록한 아이디어 목록을 페이징하여 조회합니다. 키워드로 제목을 검색할 수 있으며, 최신순(requestedAt DESC)으로 정렬됩니다."
	)
	@GetMapping
	public ResponseEntity<ApiResponse<Page<IdeaSummaryResponse>>> getMyIdeas(
		@Parameter(hidden = true) @ValidatedUser User user,
		@Parameter(description = "페이징 정보 (size=8, sort=requestedAt,desc)", hidden = true)
		@PageableDefault(size = 8, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable,
		@Parameter(description = "검색 키워드 (제목 검색)", example = "아이디어")
		@RequestParam(value = "keyword", required = false) String keyword
	) {
		Page<IdeaSummaryResponse> ideas = ideaService.getIdeasByUser(user, pageable, keyword);
		return ResponseEntity.ok(ApiResponse.success(ideas));
	}

	@Operation(
		summary = "아이디어 상세 조회",
		description = "특정 아이디어의 모든 버전에 대해 최신순으로 조회합니다."
	)
	@GetMapping("/{ideaId}")
	public ResponseEntity<ApiResponse<IdeaWithVersionsResponse>> getIdea(
			@Parameter(hidden = true) @ValidatedUser User user,
			@Parameter(description = "조회할 아이디어 ID", required = true, example = "1")
			@PathVariable("ideaId") Long ideaId
	) {
		IdeaWithVersionsResponse response = ideaService.getIdeaVersions(user, ideaId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(
		summary = "아이디어 삭제",
		description = "아이디어를 삭제하고 파일 원본(S3)·fileHash·salt·서명을 파기합니다. "
			+ "블록체인의 commit 은 지울 수 없지만, 파기 후에는 commit 만으로 원본이나 사용자를 연결할 수 없습니다."
	)
	@DeleteMapping("/{ideaId}")
	public ResponseEntity<ApiResponse<Void>> deleteIdea(
		@Parameter(hidden = true) @ValidatedUser User user,
		@Parameter(description = "삭제할 아이디어 ID", required = true, example = "1")
		@PathVariable("ideaId") Long ideaId
	) {
		ideaService.deleteIdea(user, ideaId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Operation(
		summary = "증명서 데이터 조회",
		description = "아이디어의 최신 버전에 대한 증명서 발급 데이터를 조회합니다. 최신 버전의 파일이 블록체인에 등록(REGISTERED)되어 있어야 합니다."
	)
	@GetMapping("/{ideaId}/certificate")
	public ResponseEntity<ApiResponse<IdeaCertificateResponse>> getCertificateData(
		@Parameter(hidden = true) @ValidatedUser User user,
		@Parameter(description = "아이디어 ID", required = true, example = "1")
		@PathVariable("ideaId") Long ideaId
	) {
		IdeaCertificateResponse response = ideaService.getCertificateData(user, ideaId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(
		summary = "블록체인 등록 재시도",
		description = "등록에 실패(FAILED)한 파일을 다시 등록 요청합니다. 이미 체인에 있는 경우 중복 기록되지 않고 등록 완료로 정정됩니다."
	)
	@PostMapping("/{ideaId}/files/{ideaFileId}/retry")
	public ResponseEntity<ApiResponse<FileRegistrationResult>> retryRegistration(
		@Parameter(hidden = true) @ValidatedUser User user,
		@PathVariable("ideaId") Long ideaId,
		@PathVariable("ideaFileId") Long ideaFileId
	) {
		return ResponseEntity.ok(ApiResponse.success(ideaService.retryRegistration(user, ideaId, ideaFileId)));
	}

	@Operation(
		summary = "독립 검증용 증명 자료(proof.json) 조회",
		description = "파일 원본과 함께 사용하면 iDear 서버 없이도 해시·commit·서명·온체인 기록을 검증할 수 있는 자료입니다. "
			+ "salt 와 서명 원문이 포함되므로 본인만 조회할 수 있습니다."
	)
	@GetMapping("/{ideaId}/files/{ideaFileId}/proof")
	public ResponseEntity<ApiResponse<IdeaProofResponse>> getProof(
		@Parameter(hidden = true) @ValidatedUser User user,
		@PathVariable("ideaId") Long ideaId,
		@PathVariable("ideaFileId") Long ideaFileId
	) {
		return ResponseEntity.ok(ApiResponse.success(ideaService.getProof(user, ideaId, ideaFileId)));
	}
}

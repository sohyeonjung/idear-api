package com.idear.backend.blockchain.controller;

import java.util.Map;

import com.idear.backend.blockchain.application.BlockchainService;
import com.idear.backend.blockchain.dto.request.RegistrationResultRequest;
import com.idear.backend.global.ApiResponse;
import com.idear.backend.idea.util.ServerSignatureService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Blockchain", description = "블록체인 관련 API")
@RestController
@RequestMapping("/blockchain")
@RequiredArgsConstructor
public class BlockchainController {

    private final BlockchainService blockchainService;
    private final ServerSignatureService serverSignatureService;

    /**
     * 게이트웨이 웹훅. 공개 엔드포인트이므로 본문을 신뢰하지 않고 체인을 재조회해 상태를 확정한다.
     * 처리 결과와 무관하게 200 을 반환해 commit 존재 여부 등을 외부에 노출하지 않는다.
     */
    @Hidden
    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<Void>> handleRegistration(@RequestBody RegistrationResultRequest request){
        blockchainService.handleRegistrationResult(request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "서버 서명 공개키 조회", description = "proof.json 의 serverSignature 를 독립 검증할 때 사용하는 공개키 (P-256, uncompressed)")
    @GetMapping("/server-public-key")
    public ResponseEntity<ApiResponse<Map<String, String>>> getServerPublicKey() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "algorithm", "ECDSA P-256 (secp256r1)",
                "publicKey", serverSignatureService.getServerPublicKey()
        )));
    }
}

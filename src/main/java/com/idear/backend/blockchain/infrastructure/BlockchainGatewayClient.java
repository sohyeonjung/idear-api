package com.idear.backend.blockchain.infrastructure;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;

/**
 * idear-chain(NestJS) 게이트웨이 호출을 한곳에 모은 클라이언트.
 * <ul>
 *   <li>POST /chain/file-proof/commits : 등록 요청 (BullMQ 큐 적재, 202)</li>
 *   <li>GET  /chain/file-proof/commits/{commit} : 체인 상태 조회 (없으면 404)</li>
 * </ul>
 * 기존 코드는 예외를 삼켜서 호출 실패를 알 수 없었다. 여기서는 실패를 예외로 올려
 * Outbox 재시도와 대사 배치가 판단할 수 있게 한다.
 */
@Component
@RequiredArgsConstructor
public class BlockchainGatewayClient {

	private final RestTemplate restTemplate;

	@Value("${blockchain.gateway.url}")
	private String gatewayUrl;

	public void requestRegistration(String commit, Long timestamp, String userSignature, String serverSignature) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		RegisterCommitRequest body = new RegisterCommitRequest(commit, timestamp, userSignature, serverSignature);
		try {
			restTemplate.postForObject(gatewayUrl + "/chain/file-proof/commits", new HttpEntity<>(body, headers), String.class);
		} catch (RestClientException e) {
			throw new GatewayUnavailableException("Gateway registration request failed: " + e.getMessage(), e);
		}
	}

	/**
	 * @return 체인에 있으면 레코드, 없으면 empty
	 * @throws GatewayUnavailableException 게이트웨이/RPC 장애로 판단할 수 없을 때 (없음과 구분해야 한다)
	 */
	@SuppressWarnings("unchecked")
	public Optional<OnChainCommit> findCommit(String commit) {
		try {
			Map<String, Object> res = restTemplate.getForObject(
					gatewayUrl + "/chain/file-proof/commits/{commit}", Map.class, commit);
			if (res == null || !Boolean.TRUE.equals(res.get("exists"))) {
				return Optional.empty();
			}
			Long blockNumber = asLong(res.get("blockNumber"));
			return Optional.of(new OnChainCommit(
					asString(res.get("commit")),
					asString(res.get("txHash")),
					blockNumber == null ? null : blockNumber.intValue(),
					asLong(res.get("registeredAt")),
					asLong(res.get("timestamp")),
					asString(res.get("userSignature")),
					asString(res.get("serverSignature"))
			));
		} catch (HttpClientErrorException.NotFound e) {
			return Optional.empty();
		} catch (RestClientException e) {
			throw new GatewayUnavailableException("Gateway commit lookup failed: " + e.getMessage(), e);
		}
	}

	private static String asString(Object v) {
		return v == null ? null : v.toString();
	}

	private static Long asLong(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof Number n) {
			return n.longValue();
		}
		return Long.parseLong(v.toString());
	}

	private record RegisterCommitRequest(String commit, Long timestamp, String userSignature, String serverSignature) {
	}
}

package com.idear.backend.blockchain.infrastructure;

/** 블록체인 게이트웨이(idear-chain)에 연결할 수 없거나 5xx 를 받은 경우. 재시도 대상이다. */
public class GatewayUnavailableException extends RuntimeException {
	public GatewayUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}

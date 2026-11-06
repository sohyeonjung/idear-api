# 블록체인 등록 신뢰성·보안 개선

> 범위: `idear-api`(이 저장소)만 수정했다. 게이트웨이(`idear-chain`), 프론트(`iDear-FE`), 컨트랙트(`FileProof.sol`)는
> 수정하지 않았고, 이 세 곳과 **하위 호환**되도록 API 쪽에서 해결했다.

## 1. 개선 전 구조와 문제

```
FE ──서명──▶ API ──(트랜잭션 안에서 HTTP, 예외 무시)──▶ Gateway ──BullMQ──▶ Sepolia
                ▲                                             │
                └──────────── 웹훅 (인증 없음, 본문 그대로 신뢰) ◀─┘ (WebSocket 이벤트 기반)
```

| # | 문제 | 결과 |
|---|---|---|
| 1 | 웹훅이 `permitAll` 이고 본문을 그대로 믿음 | 누구나 가짜 success 를 보내 **체인에 없는 증명서** 발급 가능 |
| 2 | 사용자 서명을 서버가 검증하지 않음 | "사용자·서버 이중 서명"이 실제로는 아무 문자열이나 통과 |
| 3 | 사용자 서명(fileHash 에 대한 서명) **원문**을 체인에 기록 | 원본 파일 + 공개키만 있으면 salt 없이 사용자와 파일 연결 가능 |
| 4 | 공개키를 덮어씀 | 키 재발급 후 과거 서명 검증 불가 |
| 5 | 트랜잭션 안에서 외부 HTTP 호출 + 예외 삼킴 | 게이트웨이 장애 시 **영구 PENDING(유실)**, 커밋 실패 시 **체인에만 기록(역전)** |
| 6 | "mempool 2초 미진입"을 실패로 확정 | 나중에 채굴되면 **체인엔 있는데 DB 는 FAILED** |
| 7 | 성공 판정을 WebSocket 이벤트·웹훅 1회에만 의존 | WS 끊김·웹훅 전송 실패 시 **복구 수단 없음** |
| 8 | 같은 웹훅 재수신 시 상태 예외 | 비멱등 |
| 9 | 상태 변경 트랜잭션 안에서 PDF·S3·메일 | 부가 작업 실패가 핵심 상태까지 롤백 |
| 10 | 삭제가 soft delete | salt·해시·원본이 남아 "삭제하면 연결이 끊긴다"가 사실이 아님 |
| 11 | RestTemplate 타임아웃 없음 | 게이트웨이 무응답 시 스레드 고갈 위험 |
| 12 | 서버 없이 검증할 수단 없음 | "서버 비의존 검증" 주장에 근거 없음 |

## 2. 개선 후 구조

```
FE ──서명──▶ API [서명 검증 → 상태 변경 + Outbox 기록] (한 트랜잭션)
                     │ commit 후
                     ▼
              Outbox Relay (2s, 지수 백오프, 5회 → DEAD) ──▶ Gateway ──▶ Sepolia
                                                               │
   웹훅(신호일 뿐) ──▶ API ──▶ Gateway GET /commits/{commit} 로 체인 재조회 ──▶ 체인 값으로 상태 확정
                                                               ▲
   대사 배치(60s) ─────────── PENDING/FAILED 건을 체인과 비교 ──┘ (복구 / 재전송 / 타임아웃)
                     │ 상태 확정 commit 후 (AFTER_COMMIT)
                     ▼
              증명서 PDF · 알림 · 이메일 (별도 트랜잭션, 실패해도 상태 유지)
```

### 핵심 원칙
1. **체인이 진실의 원천(Source of Truth)**: 웹훅 본문이 아니라 체인에서 읽은 txHash·blockNumber·블록 타임스탬프로 확정한다.
2. **최소 한 번 전송 + 최종 멱등**: Outbox 가 "보낼 일"을 잃지 않고, 중복 전송은 컨트랙트 commit 유니크와 상태 전이 규칙이 막는다.
3. **타임아웃 ≠ 실패**: FAILED 도 일정 기간 체인을 재확인해 정정한다.
4. **장애와 '없음'을 구분**: 게이트웨이 조회 실패 시 상태를 바꾸지 않는다.

## 3. 상태 전이

```
PENDING_USER_SIGNATURE ──(서명 검증 통과)──▶ BLOCKCHAIN_PENDING ──(체인 확인)──▶ REGISTERED
                                               │   ▲                                ▲
                          (체인에 없음 + 실패 보고 │   │ (재시도 API)                    │ (대사: 나중에 채굴 확인)
                           / Outbox DEAD /        ▼   │                                │
                           재전송 한도 초과)      FAILED ──────────────────────────────┘
```

| 전이 | 허용 조건 | 멱등 처리 |
|---|---|---|
| → REGISTERED | 체인에 commit 존재 + (v2) 서명 다이제스트 일치 | 이미 REGISTERED 면 무시 |
| → FAILED | BLOCKCHAIN_PENDING 이고 체인에 없음 | 이미 FAILED/REGISTERED 면 무시 |
| FAILED → PENDING | 사용자 재시도, `ALREADY_REGISTERED` 제외 | — |

## 4. 규제·설계 의사결정표

| 쟁점 | 위험 | 설계 결정 |
|---|---|---|
| 개인정보 온체인 저장 | 삭제·정정 불가 | 원본은 S3·RDS, 체인에는 commit 만 |
| 파일 해시 직접 기록 | 원본 추정·연결 | 256bit salt 기반 commit |
| 사용자 서명 원문 온체인 | salt 우회 연결 | 서명 SHA-256 다이제스트만 기록(v2) |
| 관리자 단독 등록 | 증거 조작 | 사용자 서명 **서버 검증** + 서버 서명 |
| 웹훅 위조 | 허위 증명서 | 웹훅 불신, 체인 재조회로 확정 |
| 외부 RPC·게이트웨이 장애 | 상태 불일치 | Outbox·백오프 재시도·DLQ·대사 배치 |
| 개인키 분실·기기 변경 | 과거 서명 검증 불가 | 공개키 이력 + 파일별 서명 키 스냅샷 |
| 삭제 요청(개인정보보호법 제21조 파기) | 온체인 값과 정보주체 연결 | 원본·fileHash·salt·서명 파기. commit 은 남지만 단독으로는 연결 불가 |
| 서버 의존 검증 | 서비스 종료 시 증명 불가 | proof.json + 브라우저 검증 도구 |

> 표현 주의: "개인정보보호법을 완벽히 준수"가 아니라 "개인정보의 직접적인 온체인 기록을 배제하고, 삭제 후 온체인 검증값과 정보주체 간 연결 가능성을 최소화하도록 설계"라고 말한다.

## 5. 검증 (테스트)

`./gradlew test`: 55개 테스트 통과 (기존 EmailServiceTest 1개는 원래 @Disabled 로 skip).

| 시나리오 | 테스트 | 결과 |
|---|---|---|
| 위조 success 웹훅 | `RegistrationConsistencyTest$Webhook.forgedSuccessIgnored` | 상태 변경 0 |
| failure 웹훅인데 실제 채굴됨 | `...failureButMined` | REGISTERED 로 확정 |
| **동일 웹훅 100회** | `...duplicateWebhooksIdempotent`, 통합테스트 | **상태 전이 1회, 알림 1건** |
| 웹훅 유실 | `$Reconciliation.recoverLostWebhook` | 대사 배치가 복구 |
| 요청 유실 | `...redispatchThenTimeout` | 재전송 2회 → 한도 초과 시 FAILED |
| 게이트웨이 장기 장애 | `$Outbox.deadLetter` | 5회 백오프 후 DEAD + FAILED |
| 잘못된 사용자 서명 | 통합테스트 `rejectInvalidSignature` | 400, Outbox 기록 없음 |
| WebCrypto ↔ Java 서명 호환 | `SignatureCryptoTest` | raw(r‖s)·DER 변환 20회 반복 검증 |
| 서버 공개키 직접 계산 정확성 | `SignatureCryptoTest.serverKeyDerivationAndSignature` | JCA 공개키와 일치 |
| Java ↔ 브라우저 검증기 호환 | 통합테스트가 만든 `build/proof-sample` 를 `verify-cli.mjs` 로 검증 | 10개 항목 PASS, 파일 1바이트 변조 시 FAIL |

## 6. 이 저장소만으로 해결하지 못한 것 (다음 단계)

- **게이트웨이 웹훅 HMAC 서명**: 지금은 "웹훅 불신 + 체인 재조회"로 위조를 무력화했지만, 게이트웨이가 HMAC 헤더를 보내면 불필요한 조회도 줄일 수 있다.
- **게이트웨이 `@OnQueueEvent('failed')`**: `@Processor` 에서는 `@OnWorkerEvent` 여야 할 가능성이 높다. 최종 실패 웹훅이 안 나가도 API 대사 배치가 복구하도록 설계했다.
- **API 가 RPC 를 직접 조회**: 지금은 체인 조회도 게이트웨이를 거친다. 게이트웨이 자체를 신뢰 경계 밖으로 빼려면 web3j 로 직접 `getCommit` 을 호출하면 된다.
- **컨트랙트 v2**: 서명 원문 대신 해시만 저장(가스 절감), `Ownable2Step`·`Pausable`, 등록자·관리자 역할 분리, 전체 열거(`getCommitsByRange`) 제거, Merkle root 배치 앵커링.
- **서명 payload 에 domain·version·nonce**: FE 수정이 필요해 이번엔 형식을 유지했다(`fileHash|timestamp`).
- **레거시(v1) 등록 건**: 이미 체인에 서명 원문이 올라간 건은 되돌릴 수 없다. 파기 시 오프체인 값만 지운다.
- **수치 측정**: 실제 Sepolia 환경에서 동기/비동기 응답시간, P95 확정 시간, 대사 복구 건수 측정 필요.

## 7. 설정

`application.yml` 의 `blockchain.*` 참고. 운영 DB 는 `docs/migration/V2__blockchain_reliability.sql` 을 먼저 실행한다.

# iDear 독립 검증 도구

iDear 서버에 접속하지 않고 **원본 파일 + proof.json** 만으로 다음을 확인한다.

1. SHA-256(파일) == fileHash (파일이 바뀌지 않았는가)
2. SHA-256(fileHash + salt) == commit (체인에 올라간 값과 이 파일이 연결되는가)
3. 사용자 전자서명 (등록자가 이 파일에 서명했는가)
4. 서버 서명
5. SHA-256(사용자 서명) == 온체인 서명 다이제스트
6. (선택) Sepolia 컨트랙트 `getCommit(commit)` 기록과 일치하는가, 블록 타임스탬프

## 사용법

proof.json 은 `GET /ideas/{ideaId}/files/{ideaFileId}/proof` 로 받는다 (본인만 조회 가능).

**CLI (Node 18+)**
```bash
node tools/proof-verifier/verify-cli.mjs ./my-idea.pdf ./proof.json
node tools/proof-verifier/verify-cli.mjs ./my-idea.pdf ./proof.json ./chain-record.json
```

**브라우저**: ES 모듈을 쓰므로 `file://` 로는 열리지 않는다. 정적 서버로 연다.
```bash
npx serve tools/proof-verifier
```
RPC URL 을 넣으면 ethers.js 로 컨트랙트를 직접 조회한다. 해시·서명 검증은 WebCrypto 로 브라우저 안에서만 계산한다.

## 주의
proof.json 에는 salt 와 서명 원문이 들어 있다. 이 파일을 공개하면 제3자가 파일과 등록자를 연결할 수 있으므로,
증명이 필요한 상대에게만 원본 파일과 함께 제공한다.

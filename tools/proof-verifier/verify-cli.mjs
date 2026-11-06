// 사용법: node tools/proof-verifier/verify-cli.mjs <원본파일> <proof.json> [chain-record.json]
// chain-record.json 은 컨트랙트 getCommit(commit) 결과(또는 게이트웨이 GET /chain/file-proof/commits/{commit} 응답)
import { readFileSync } from 'node:fs';
import { verifyProof } from './verifier-core.js';

const [, , filePath, proofPath, chainPath] = process.argv;
if (!filePath || !proofPath) {
  console.error('usage: node verify-cli.mjs <file> <proof.json> [chain-record.json]');
  process.exit(2);
}
const results = await verifyProof(
  new Uint8Array(readFileSync(filePath)),
  JSON.parse(readFileSync(proofPath, 'utf8')),
  chainPath ? JSON.parse(readFileSync(chainPath, 'utf8')) : null,
);
let failed = 0;
for (const r of results) {
  const mark = r.ok === null ? 'SKIP' : r.ok ? 'PASS' : 'FAIL';
  if (r.ok === false) failed++;
  console.log(`[${mark}] ${r.name}${r.detail ? '  — ' + r.detail : ''}`);
}
console.log(failed === 0 ? '\n검증 성공' : `\n검증 실패 ${failed}건`);
process.exit(failed === 0 ? 0 : 1);

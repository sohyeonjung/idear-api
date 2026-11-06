// iDear 독립 검증 로직 (브라우저 / Node 18+ 공용, WebCrypto 만 사용)
// iDear 서버에 접속하지 않고 "파일 + proof.json (+ 체인 조회 결과)" 만으로 검증한다.

const subtle = globalThis.crypto.subtle;
const enc = new TextEncoder();

const strip0x = (h) => (h.startsWith('0x') || h.startsWith('0X') ? h.slice(2) : h);
export const hexToBytes = (hex) => {
  const h = strip0x(hex);
  if (h.length % 2 !== 0 || /[^0-9a-fA-F]/.test(h)) throw new Error('invalid hex');
  const out = new Uint8Array(h.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(h.substr(i * 2, 2), 16);
  return out;
};
export const bytesToHex = (b) => Array.from(new Uint8Array(b), (x) => x.toString(16).padStart(2, '0')).join('');
const b64ToBytes = (b64) => {
  const bin = atob(b64.trim());
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
};
const sha256 = async (bytes) => new Uint8Array(await subtle.digest('SHA-256', bytes));

// ASN.1 DER ECDSA 서명 → IEEE P1363 (r||s, 각 32바이트). WebCrypto 는 P1363 만 받는다.
export function derToRaw(der) {
  if (der.length === 64) return der;
  let o = 2;
  if (der[1] & 0x80) o = 2 + (der[1] & 0x7f);
  const rLen = der[o + 1];
  const r = der.slice(o + 2, o + 2 + rLen);
  const so = o + 2 + rLen;
  const sLen = der[so + 1];
  const s = der.slice(so + 2, so + 2 + sLen);
  const pad = (x) => {
    const t = x.length > 32 ? x.slice(x.length - 32) : x;
    const p = new Uint8Array(32);
    p.set(t, 32 - t.length);
    return p;
  };
  const out = new Uint8Array(64);
  out.set(pad(r), 0);
  out.set(pad(s), 32);
  return out;
}

const ecdsa = { name: 'ECDSA', namedCurve: 'P-256' };

/**
 * @param {Uint8Array} fileBytes 원본 파일
 * @param {object} proof proof.json (API 응답의 data 또는 그 자체)
 * @param {object|null} chain { exists, timestamp, userSignature, serverSignature, blockNumber, registeredAt } — 컨트랙트 getCommit 결과
 * @returns {Promise<{name:string, ok:boolean|null, detail:string}[]>} ok=null 은 '검사 안 함'
 */
export async function verifyProof(fileBytes, proof, chain = null) {
  const p = proof.data ?? proof;
  const results = [];
  const check = (name, ok, detail = '') => results.push({ name, ok, detail });

  // 1. 파일 해시
  const fileHash = bytesToHex(await sha256(fileBytes));
  check('1. 파일 해시 일치 (SHA-256(파일) == fileHash)', fileHash === p.fileHash, `계산값 ${fileHash}`);

  // 2. commit 재계산: salt 를 가진 사람만 할 수 있다
  const commit = '0x' + bytesToHex(await sha256(enc.encode(p.fileHash + p.salt)));
  check('2. commit 재계산 (SHA-256(fileHash + salt) == commit)', commit.toLowerCase() === p.commit.toLowerCase(), `계산값 ${commit}`);

  // 3. 사용자 서명: 메시지는 proof 에 적힌 문자열을 믿지 않고 직접 다시 만든다
  const userMessage = `${p.fileHash}|${p.requestedTimestamp}`;
  try {
    const key = await subtle.importKey('spki', b64ToBytes(p.userPublicKey), ecdsa, false, ['verify']);
    const ok = await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key,
      derToRaw(hexToBytes(p.userSignature)), enc.encode(userMessage));
    check('3. 사용자 전자서명 검증 (공개키로 fileHash|timestamp 서명 확인)', ok, `message="${userMessage}"`);
  } catch (e) {
    check('3. 사용자 전자서명 검증', false, e.message);
  }

  // 4. 서버 서명: 서버는 SHA-256(message) 를 다시 SHA256withECDSA 로 서명한다 (두 번 해시)
  const serverMessage = `${p.commit}|${p.requestedTimestamp}|${p.userSignature}`;
  try {
    const key = await subtle.importKey('raw', hexToBytes(p.serverPublicKey), ecdsa, false, ['verify']);
    const once = await sha256(enc.encode(serverMessage));
    const ok = await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key,
      derToRaw(hexToBytes(p.serverSignature)), once);
    check('4. 서버 서명 검증 (commit|timestamp|userSignature)', ok, '');
  } catch (e) {
    check('4. 서버 서명 검증', false, e.message);
  }

  // 5. 온체인 사용자 서명 다이제스트 (v2)
  if ((p.proofVersion ?? 1) >= 2) {
    const digest = '0x' + bytesToHex(await sha256(hexToBytes(p.userSignature)));
    check('5. 온체인 서명 다이제스트 (SHA-256(userSignature) == onChainUserSignature)',
      digest.toLowerCase() === String(p.onChainUserSignature).toLowerCase(), `계산값 ${digest}`);
  } else {
    check('5. 온체인 서명 다이제스트', null, 'v1(레거시) 증명: 체인에 서명 원문이 저장됨');
  }

  // 6. 체인 기록
  if (!chain) {
    check('6. 컨트랙트 기록 확인', null, 'RPC URL 을 입력하면 getCommit(commit) 결과와 비교합니다');
  } else {
    const same = (a, b) => String(a).toLowerCase() === String(b).toLowerCase();
    check('6-1. 컨트랙트에 commit 존재', chain.exists === true, '');
    check('6-2. 기록된 timestamp 일치', Number(chain.timestamp) === Number(p.requestedTimestamp), `chain=${chain.timestamp}`);
    check('6-3. 기록된 사용자 서명(다이제스트) 일치', same(chain.userSignature, p.onChainUserSignature), '');
    check('6-4. 기록된 서버 서명 일치', same(chain.serverSignature, p.serverSignature), '');
    if (chain.registeredAt) {
      check('6-5. 블록 타임스탬프 (증명력 있는 등록 시각)', true,
        new Date(Number(chain.registeredAt) * 1000).toISOString() + ` / block #${chain.blockNumber}`);
    }
  }
  return results;
}

-- iDear 블록체인 정합성 개선 마이그레이션 (MySQL)
-- 운영 DB 는 ddl-auto: update 라서 새 테이블·컬럼은 자동 생성되지만,
-- 기존 컬럼의 NOT NULL 해제는 Hibernate 가 하지 않으므로 아래 ALTER 를 직접 실행해야 한다.

-- 1) 파기(purge) 시 fileHash·salt 를 지울 수 있도록 NULL 허용
ALTER TABLE idea_files MODIFY file_hash VARCHAR(64) NULL;
ALTER TABLE idea_files MODIFY salt VARCHAR(64) NULL;

-- 2) 신규 컬럼(signer_public_key, on_chain_user_signature, proof_version, status_changed_at, dispatch_count)과
--    신규 테이블(blockchain_outbox, user_public_keys)은 애플리케이션 기동 시 ddl-auto: update 가 생성한다.
--    (MySQL 8 은 ADD COLUMN IF NOT EXISTS 를 지원하지 않으므로 여기서 중복 생성하지 않는다)
--    아래 3), 4) 는 애플리케이션을 한 번 기동해 컬럼·테이블이 생긴 뒤 실행한다.

-- 3) 기존 행 보정: 서명 당시 공개키를 현재 사용자 공개키로 백필 (키 재발급 이력이 없던 시기의 최선값)
UPDATE idea_files f
  JOIN idea_version_files vf ON vf.idea_file_id = f.idea_file_id
  JOIN idea_versions v ON v.idea_version_id = vf.idea_version_id
  JOIN ideas i ON i.idea_id = v.idea_id
  JOIN users u ON u.user_id = i.user_id
SET f.signer_public_key = u.public_key
WHERE f.signer_public_key IS NULL AND f.user_signature IS NOT NULL;

UPDATE idea_files SET proof_version = 1 WHERE proof_version IS NULL AND user_signature IS NOT NULL;
UPDATE idea_files SET dispatch_count = 1 WHERE register_status <> 'PENDING_USER_SIGNATURE' AND dispatch_count = 0;

-- 4) 공개키 이력: 현재 등록된 키를 첫 이력으로
INSERT INTO user_public_keys (user_id, public_key, created_at)
SELECT user_id, public_key, NOW(6) FROM users
WHERE public_key IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_public_keys k WHERE k.user_id = users.user_id);

#!/usr/bin/env node
// Capture worktree state before each prompt. Never modify project files.
const { execFileSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

function digest(value) { return createHash('sha256').update(value).digest('hex'); }
function relevant(file) {
  return /^(apps\/web\/(src\/.*\.(tsx?|css)|.*\.(tsx?|mjs))|services\/(auth|control|data|ingestion)\/src\/.*\.(java|sql))$/.test(file);
}
function snapshot(root) {
  const result = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: root });
  const files = [...new Set(result.toString('utf8').split('\0').filter(relevant))];
  const values = {};
  for (const file of files) {
    try { values[file] = digest(fs.readFileSync(path.join(root, file))); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  return values;
}
try {
  const input = JSON.parse(fs.readFileSync(0, 'utf8'));
  const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: input.cwd || process.cwd(), encoding: 'utf8' }).trim();
  if (!input.session_id) throw new Error('session_id 없음');
  const stateDir = path.join(os.tmpdir(), 'weavo-codex-convention');
  const statePath = path.join(stateDir, digest(root + '\0' + input.session_id) + '.json');
  if (process.argv[2] === 'snapshot') {
    fs.mkdirSync(stateDir, { recursive: true, mode: 0o700 });
    fs.writeFileSync(statePath, JSON.stringify(snapshot(root)), { mode: 0o600 });
    process.stdout.write('{}');
  } else if (process.argv[2] === 'review') {
    if (input.stop_hook_active || !fs.existsSync(statePath)) { process.stdout.write('{}'); process.exit(0); }
    const before = JSON.parse(fs.readFileSync(statePath, 'utf8'));
    const after = snapshot(root);
    const changed = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter((file) => before[file] !== after[file]).sort();
    if (!changed.length) { process.stdout.write('{}'); process.exit(0); }
    const front = changed.some((file) => file.startsWith('apps/web/'));
    const back = changed.some((file) => file.startsWith('services/'));
    const tasks = [];
    if (front) tasks.push('프론트: apps/web/AGENTS.md와 README의 구조, Server/Client 경계, 상태·useEffect·오류 처리·테스트를 검토하고 적절한 lint/typecheck/test를 실행');
    if (back) tasks.push('Spring: 변경된 서비스의 AGENTS.md와 README에 따라 기능/계층 책임, Entity·DTO, 주입·트랜잭션·오류 처리·테스트를 검토하고 해당 서비스의 spotlessCheck/test를 실행');
    if (front && back) tasks.push('양쪽을 변경했으므로 API 요청·응답·오류 계약과 관련 테스트의 일치 여부를 확인');
    const reason = `이번 요청 중 바뀐 코드에 대해 컨벤션 검토를 한 번 수행하세요. 기존 사용자 변경을 수정하지 말고, 실행 불가한 검사는 이유를 보고하세요.\n${tasks.join('\n')}\n변경 파일:\n${changed.slice(0, 80).join('\n')}${changed.length > 80 ? '\n(일부 생략)' : ''}`;
    process.stdout.write(JSON.stringify({ decision: 'block', reason }));
  } else { throw new Error('알 수 없는 모드'); }
} catch (error) {
  // A missing baseline must never cause unrelated pre-existing work to be reviewed.
  process.stdout.write(JSON.stringify({ systemMessage: `Weavo 컨벤션 훅을 실행하지 못했습니다: ${error.message}` }));
}

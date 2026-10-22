/**
 * 포키 후원 연동 인증 페이지 (Cloudflare Worker)
 *
 * 하는 일은 딱 세 가지다.
 *   GET /chzzk/start?state=…     게임에서 받은 링크 → 치지직 로그인·권한 허용 화면으로 보냄
 *   GET /chzzk/callback?code=…   치지직이 돌려보낸 인증 코드를 잠깐 보관하고 "완료" 화면을 보여줌
 *   GET /chzzk/claim?state=…     서버 플러그인이 비밀키(X-Poki-Key)로 와서 코드를 가져감 (한 번 가져가면 삭제)
 *
 * 토큰 발급(Client Secret 사용)은 플러그인이 직접 한다. 여기에는 Client Secret 이 없다.
 * 서버 집 IP 는 여기 어디에도 나오지 않는다 — 플러그인이 이쪽으로 요청하는 방향만 있다.
 */

const STATE_RE = /^[A-Za-z0-9_-]{24,64}$/;
const CODE_TTL_MS = 10 * 60 * 1000; // 코드 보관 10분

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== 'GET') return text('Method Not Allowed', 405);

    try {
      switch (url.pathname) {
        case '/chzzk/start':
          return start(url, env);
        case '/chzzk/callback':
          return await callback(url, env);
        case '/chzzk/claim':
          return await claim(request, url, env);
        case '/':
          return page('포키 인증', '포키 서버 치지직 연동 페이지예요.', '게임 안에서 <b>/후원 연동</b> 을 입력하면 링크가 나와요.');
        default:
          return text('Not Found', 404);
      }
    } catch (e) {
      console.error(e);
      return page('잠깐 문제가 생겼어요', '다시 시도해 주세요.', '계속 안 되면 운영진에게 알려주세요.', 500);
    }
  },
};

function start(url, env) {
  const state = url.searchParams.get('state') || '';
  if (!STATE_RE.test(state)) {
    return page('링크가 올바르지 않아요', '게임에서 <b>/후원 연동</b> 을 다시 입력해 새 링크를 받아주세요.', '', 400);
  }
  const target = new URL('https://chzzk.naver.com/account-interlock');
  target.searchParams.set('clientId', env.CHZZK_CLIENT_ID);
  target.searchParams.set('redirectUri', env.REDIRECT_URI);
  target.searchParams.set('state', state);
  return Response.redirect(target.toString(), 302);
}

async function callback(url, env) {
  const state = url.searchParams.get('state') || '';
  const code = url.searchParams.get('code') || '';
  if (!STATE_RE.test(state) || !code) {
    return page('연동이 취소됐어요', '권한 허용을 하지 않았거나 링크가 만료됐어요.', '게임에서 <b>/후원 연동</b> 을 다시 입력해 주세요.', 400);
  }
  await ensureTable(env);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('DELETE FROM pending WHERE created_at < ?').bind(now - CODE_TTL_MS),
    env.DB.prepare('INSERT OR REPLACE INTO pending (state, code, created_at) VALUES (?, ?, ?)').bind(state, code, now),
  ]);
  return page('인증 완료!', '이제 게임으로 돌아가면 연동이 끝나요.', '보통 몇 초 안에 게임 채팅에 "연동 완료" 가 떠요. 이 창은 닫아도 돼요.');
}

async function claim(request, url, env) {
  if (!env.BRIDGE_SECRET) return json({ error: 'bridge secret not set' }, 500);
  if (!(await sameSecret(request.headers.get('X-Poki-Key') || '', env.BRIDGE_SECRET))) {
    return json({ error: 'unauthorized' }, 401);
  }
  const state = url.searchParams.get('state') || '';
  if (!STATE_RE.test(state)) return json({ error: 'bad state' }, 400);

  await ensureTable(env);
  const row = await env.DB.prepare('SELECT code, created_at FROM pending WHERE state = ?').bind(state).first();
  if (!row) return new Response(null, { status: 204 });
  await env.DB.prepare('DELETE FROM pending WHERE state = ?').bind(state).run();
  if (Date.now() - row.created_at > CODE_TTL_MS) return new Response(null, { status: 204 });
  return json({ code: row.code });
}

let tableReady = false;
async function ensureTable(env) {
  if (tableReady) return;
  await env.DB.prepare(
    'CREATE TABLE IF NOT EXISTS pending (state TEXT PRIMARY KEY, code TEXT NOT NULL, created_at INTEGER NOT NULL)'
  ).run();
  tableReady = true;
}

/** 길이가 달라도 시간이 새지 않게 해시끼리 비교 */
async function sameSecret(a, b) {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest('SHA-256', enc.encode(a)),
    crypto.subtle.digest('SHA-256', enc.encode(b)),
  ]);
  return crypto.subtle.timingSafeEqual(ha, hb);
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}

function text(body, status) {
  return new Response(body, { status, headers: { 'content-type': 'text/plain; charset=utf-8' } });
}

function page(title, lead, note, status = 200) {
  const html = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${title} · POKI</title>
<style>
  :root { --bg:#fff7ef; --card:#ffffff; --ink:#2b2320; --quiet:#7a6a62; --accent:#e4572e; --line:#f0e2d6; }
  @media (prefers-color-scheme: dark) { :root { --bg:#1b1715; --card:#26201d; --ink:#f4ece6; --quiet:#b5a59b; --accent:#ff7a4d; --line:#3a312c; } }
  * { box-sizing:border-box; }
  body { margin:0; min-height:100vh; display:grid; place-items:center; padding:24px 16px;
         background:var(--bg); color:var(--ink); font-family:system-ui,-apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif; }
  main { width:100%; max-width:420px; background:var(--card); border:1px solid var(--line); border-radius:16px; padding:32px 24px; text-align:center; }
  .brand { font-weight:800; letter-spacing:.12em; color:var(--accent); font-size:14px; }
  h1 { margin:12px 0 8px; font-size:22px; }
  p { margin:8px 0; line-height:1.6; }
  .note { color:var(--quiet); font-size:14px; }
</style>
</head>
<body>
<main>
  <div class="brand">POKI</div>
  <h1>${title}</h1>
  <p>${lead}</p>
  ${note ? `<p class="note">${note}</p>` : ''}
</main>
</body>
</html>`;
  return new Response(html, {
    status,
    headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' },
  });
}

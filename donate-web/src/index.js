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
          return page('치지직 후원 연동', '포키 서버와 치지직 채널을 연결하는 페이지예요.', '게임 안에서 <kbd>/후원 연동</kbd> 을 입력하면 연동 버튼이 나와요.');
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
    return page('링크가 올바르지 않아요', '게임에서 <kbd>/후원 연동</kbd> 을 다시 입력해 새 링크를 받아주세요.', '', 400);
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
    return page('연동이 취소됐어요', '권한 허용을 하지 않았거나 링크가 만료됐어요.', '게임에서 <kbd>/후원 연동</kbd> 을 다시 입력해 주세요.', 400);
  }
  await ensureTable(env);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('DELETE FROM pending WHERE created_at < ?').bind(now - CODE_TTL_MS),
    env.DB.prepare('INSERT OR REPLACE INTO pending (state, code, created_at) VALUES (?, ?, ?)').bind(state, code, now),
  ]);
  return page('인증 완료!', '이제 게임으로 돌아가면 연동이 끝나요.', '보통 몇 초 안에 게임 채팅에 <b>연동 완료</b> 가 떠요. 이 창은 닫아도 돼요.', 200, 'ok');
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

const ASSET = 'https://poki-launcher.devaechu.com';

const ICONS = {
  ok: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  error: '<path d="M7 7l10 10M17 7L7 17"/>',
  info: '<path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1"/><path d="M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1"/>',
};

/** 포키 런처 사이트와 같은 디자인 (어두운 배경 · Pretendard · 하늘색 포인트 · 둥둥 뜨는 로고) */
function page(title, lead, note, status = 200, kind = status >= 400 ? 'error' : 'info') {
  const html = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<meta name="theme-color" content="#0a0e14">
<title>${title} · POKI</title>
<link rel="icon" href="${ASSET}/favicon.ico" sizes="any">
<link rel="apple-touch-icon" href="${ASSET}/img/icon.png">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css">
<style>
  :root{
    --bg:#0a0e14; --surface:#111827; --card:#1a2332; --line:rgba(148,163,184,.12);
    --text:#e2e8f0; --muted:#94a3b8; --faint:#64748b;
    --cyan:#22d3ee; --blue:#60a5fa; --pink:#ff5fa2;
  }
  *{box-sizing:border-box}
  body{margin:0;min-height:100vh;display:flex;flex-direction:column;background:var(--bg);color:var(--text);
       font-family:Pretendard,system-ui,-apple-system,sans-serif;line-height:1.65;-webkit-font-smoothing:antialiased}
  a{color:inherit}
  .hero{position:relative;flex:1;overflow:hidden;isolation:isolate;padding:24px 0 56px}
  .hero::before{content:"";position:absolute;inset:0;z-index:-2;background:url(${ASSET}/img/bg.webp) center 30%/cover no-repeat}
  .hero::after{content:"";position:absolute;inset:0;z-index:-1;
       background:linear-gradient(180deg,rgba(10,14,20,.25) 0%,rgba(10,14,20,.6) 50%,var(--bg) 100%)}
  .wrap{max-width:960px;margin:0 auto;padding:0 20px}
  nav{display:flex;justify-content:space-between;align-items:center;padding:4px 0 8px}
  .brand{display:flex;align-items:center;gap:10px;text-decoration:none;font-weight:800;text-shadow:0 1px 8px rgba(0,0,0,.5)}
  .brand img{width:32px;height:32px;border-radius:8px}
  .links a{font-size:14px;font-weight:600;text-decoration:none;margin-left:16px;opacity:.9;text-shadow:0 1px 6px rgba(0,0,0,.6)}
  .links a:hover{opacity:1}
  .inner{text-align:center;padding-top:8px}
  .logo{width:min(380px,78vw);height:auto;display:block;margin:0 auto;
        filter:drop-shadow(0 12px 30px rgba(0,0,0,.45));animation:float 5s ease-in-out infinite}
  @keyframes float{50%{transform:translateY(-8px)}}
  @media (prefers-reduced-motion:reduce){.logo{animation:none}}
  .card{max-width:440px;margin:18px auto 0;background:rgba(17,24,39,.82);border:1px solid var(--line);
        border-radius:20px;padding:28px 24px 24px;backdrop-filter:blur(10px);box-shadow:0 20px 50px -20px rgba(0,0,0,.7)}
  .ic{width:56px;height:56px;margin:0 auto 14px;border-radius:16px;display:grid;place-items:center}
  .ic svg{width:28px;height:28px;fill:none;stroke:currentColor;stroke-width:2.6;stroke-linecap:round;stroke-linejoin:round}
  .ok .ic{background:rgba(34,211,238,.15);color:var(--cyan);box-shadow:0 0 0 1px rgba(34,211,238,.35)}
  .error .ic{background:rgba(255,95,162,.14);color:var(--pink);box-shadow:0 0 0 1px rgba(255,95,162,.35)}
  .info .ic{background:rgba(96,165,250,.15);color:var(--blue);box-shadow:0 0 0 1px rgba(96,165,250,.35)}
  h1{font-size:24px;letter-spacing:-.02em;margin:0 0 6px}
  .lead{margin:0;color:var(--text)}
  .note{margin:10px 0 0;color:var(--muted);font-size:14px}
  b{color:#fff}
  kbd{font-family:inherit;font-size:13px;background:var(--card);border:1px solid var(--line);border-radius:6px;padding:1px 7px;color:var(--text)}
  .chips{display:flex;flex-wrap:wrap;justify-content:center;gap:8px;margin-top:18px}
  .chip{font-size:13px;font-weight:700;padding:4px 12px;border-radius:999px;background:rgba(10,14,20,.6);border:1px solid rgba(255,255,255,.15)}
  footer{border-top:1px solid var(--line);padding:20px 0 28px;color:var(--faint);font-size:13px}
  footer .wrap{display:flex;flex-wrap:wrap;gap:12px;justify-content:space-between}
  footer a{color:var(--muted);text-decoration:none;margin-right:14px}
  footer a:hover{color:var(--text)}
  @media (max-width:520px){.links a:not(.keep){display:none}}
</style>
</head>
<body>
<header class="hero">
  <div class="wrap">
    <nav>
      <a class="brand" href="/"><img src="${ASSET}/img/icon.png" alt="">포키 연동</a>
      <div class="links">
        <a href="${ASSET}">런처</a>
        <a class="keep" href="https://poki.devaechu.com">위키</a>
      </div>
    </nav>
    <div class="inner">
      <img class="logo" src="${ASSET}/img/logo.webp" width="1200" height="798" alt="POKI">
      <div class="card ${kind}">
        <div class="ic"><svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[kind] || ICONS.info}</svg></div>
        <h1>${title}</h1>
        <p class="lead">${lead}</p>
        ${note ? `<p class="note">${note}</p>` : ''}
        ${kind === 'info' ? '<div class="chips"><span class="chip">치지직 후원 연동</span><span class="chip">입주민 · 운영진 전용</span></div>' : ''}
      </div>
    </div>
  </div>
</header>
<footer>
  <div class="wrap">
    <span>POKI · 코블몬 서버</span>
    <span><a href="https://poki.devaechu.com">위키</a><a href="${ASSET}">런처</a></span>
  </div>
</footer>
</body>
</html>`;
  return new Response(html, {
    status,
    headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' },
  });
}

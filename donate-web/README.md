# donate-web (auth.poki.devaechu.com)

PokiDonate 의 치지직 연동 인증 페이지. Cloudflare Worker + D1(`poki-auth`).

| 주소 | 하는 일 |
| --- | --- |
| `/chzzk/start?state=…` | 게임에서 받은 링크 → 치지직 권한 허용 화면으로 보냄 |
| `/chzzk/callback` | 치지직이 돌려보낸 인증 코드를 10분간 보관, "인증 완료" 화면 |
| `/chzzk/claim?state=…` | 플러그인이 `X-Poki-Key` 비밀키로 코드를 가져감 (한 번 가져가면 삭제) |

Client Secret 은 여기 없다. 토큰 발급은 서버 플러그인이 직접 한다.
요청은 항상 플러그인 → 여기 방향이라 서버 집 IP 가 드러나지 않는다.

## 배포

`main` 에 `donate-web/` 변경이 올라가면 GitHub Actions(`인증 페이지 배포`)가 자동 배포한다.
레포 Settings → Secrets and variables → Actions 에 세 개가 필요하다.

| 이름 | 값 |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | Cloudflare → My Profile → API Tokens → "Edit Cloudflare Workers" 템플릿으로 만든 토큰 (+ D1 Edit 권한) |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare 대시보드 Workers 화면 오른쪽의 Account ID |
| `POKI_BRIDGE_SECRET` | 아무 긴 랜덤 문자열. 플러그인 `config.yml` 의 `bridge.secret` 과 똑같이 |

치지직 개발자센터 앱의 로그인 리디렉션 URL 은 `https://auth.poki.devaechu.com/chzzk/callback`.
Cloudflare 에 이 주소로 DNS 레코드를 미리 만들지 말 것 (Custom Domain 이 알아서 만든다).

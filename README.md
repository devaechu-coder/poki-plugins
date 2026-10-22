# poki-plugins

포키 서버용 직접 만든 플러그인 모음.

| 플러그인 | 설명 |
| --- | --- |
| [PokiName](PokiName/) | 한글닉 이름표 · Tab 목록 이름 · Tab 환영 문구 (TAB 대체, 패킷 미사용) |
| [PokiLinks](PokiLinks/) | `/위키`, `/런처` 같은 클릭 바로가기 링크 |
| [PokiDonate](PokiDonate/) | 치지직 후원 연동 · 후원 장부 (`/후원`) |

각 폴더에서 `mvn package` 로 빌드.

[donate-web](donate-web/) 은 PokiDonate 의 치지직 연동 인증 페이지(Cloudflare Worker, auth.poki.devaechu.com).

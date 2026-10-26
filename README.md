# poki-plugins

포키 서버용 직접 만든 플러그인 모음.

| 플러그인 | 설명 |
| --- | --- |
| [PokiName](PokiName/) | 한글닉 이름표 · 칭호 · Tab 목록 이름 · Tab 환영 문구 (TAB 대체, 패킷 미사용) |
| [PokiLinks](PokiLinks/) | `/위키`, `/런처` 같은 클릭 바로가기 링크 |
| [PokiDonate](PokiDonate/) | 치지직 후원 연동 · 후원 장부 (`/후원`) |
| [PokiStats](PokiStats/) | 운영 통계 — 동접 · 접속 기록 · 후원 통계를 통계 서버로 전송 (`/통계`, OP) |
| [PokiHelp](PokiHelp/) | 문의 접수 — `/문의`, `/신고` 는 운영진한테만, 운영진 알림 · 문의함 GUI (`/문의함`) |
| [PokiUI](PokiUI/) | 다른 플러그인 상자 제목을 포키 UI 그림 제목으로 바꿈 (SimpleShopJS 상점 7개) |
| [PokiMaintenance](PokiMaintenance/) | 점검 모드 — 점검 중엔 운영진만 접속 (`/점검 시작 22:00`, `/점검 끝`), 런처에 점검 표시 |

각 폴더에서 `mvn package` 로 빌드.

[PokiTitles-RP](PokiTitles-RP/) 는 칭호 배지 + 문의함 GUI 리소스팩 (PokiName 칭호, PokiHelp 문의함용).
main 에 올리면 Actions 가 zip 으로 Releases 에 올리고, 서버에는 트레이너 스킨 팩과 같이 resourcepacks-poki.zip 으로 묶어서 R2 에 올린다.

[donate-web](donate-web/) 은 PokiDonate 의 치지직 연동 인증 페이지(Cloudflare Worker, auth.poki.devaechu.com).

# poki-plugins

포키 서버용 직접 만든 플러그인 모음.

| 플러그인 | 설명 |
| --- | --- |
| [PokiName](PokiName/) | 한글닉 이름표 · 칭호 · Tab 목록 이름 · Tab 환영 문구 (TAB 대체, 패킷 미사용) |
| [PokiLinks](PokiLinks/) | `/위키`, `/런처` 같은 클릭 바로가기 링크 |
| [PokiDonate](PokiDonate/) | 치지직 후원 연동 · 후원 장부 (`/후원`) |
| [PokiWardrobe](PokiWardrobe/) | 치장 — 옷장 GUI(`/치장`), 모자, 파티클 |

각 폴더에서 `mvn package` 로 빌드.

[PokiTitles-RP](PokiTitles-RP/) 는 칭호 배지 이미지 리소스팩 (PokiName 칭호용), [PokiWardrobe-RP](PokiWardrobe-RP/) 는 치장 모자 리소스팩.
둘 다 main 에 올리면 Actions 가 zip 으로 Releases 에 올리고, 서버에는 트레이너 스킨 팩과 같이 resourcepacks-poki.zip 으로 묶어서 R2 에 올린다.

[donate-web](donate-web/) 은 PokiDonate 의 치지직 연동 인증 페이지(Cloudflare Worker, auth.poki.devaechu.com).

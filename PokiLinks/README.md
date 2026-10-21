# PokiLinks

채팅에 클릭 가능한 바로가기 링크를 띄워주는 플러그인.

## 명령어 (누구나)

| 명령어 | 설명 |
| --- | --- |
| `/위키` (`/wiki`) | 포키 위키 링크 |
| `/런처` (`/launcher`) | 런처 다운로드 링크 |
| `/링크` (`/links`) | 등록된 링크 전부 보기 |
| `/pokilinks reload` | config.yml 다시 불러오기 (OP) |

## 설정

`plugins/PokiLinks/config.yml` 에서 제목·설명·주소·버튼 문구를 바꿀 수 있다.
`links` 아래에 항목을 추가하면 `/링크` 목록에 나온다. (전용 명령어는 plugin.yml 에 추가해야 함)

클릭 링크는 바닐라 `/tellraw` 로 보낸다 (아크라이트에서도 추가 API 없이 동작).

## 빌드

`mvn package`

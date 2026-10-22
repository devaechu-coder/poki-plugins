# PokiTitles-RP

PokiName 칭호용 배지 이미지 리소스팩. 마크 1.21.1 (pack_format 34).

배지 한 장 = 글자 하나. `assets/minecraft/font/default.json` 에서 `\ue101` ~ `\ue114` 글자를 배지 PNG 로 바꾼다.
PokiName 이 머리 위 텍스트 디스플레이에 이 글자를 띄우면 배지로 보인다. (리소스팩이 없으면 네모로 보임)

| 글자 | id | 칭호 |
| --- | --- | --- |
| `\ue101` | beginner | 초보 모험가 |
| `\ue102` | growth | 성장의 길 |
| `\ue103` | warrior_path | 전사의 길 |
| `\ue104` | miner | 광산의 달인 |
| `\ue105` | fisher | 낚시의 달인 |
| `\ue106` | explorer | 탐험가 |
| `\ue107` | villager | 마을의 방문자 |
| `\ue108` | poki_friend | 포키의 친구 |
| `\ue109` | summer | 여름축제 참가자 |
| `\ue10a` | halloween | 할로윈의 주인공 |
| `\ue10b` | trainer | 포켓몬 트레이너 |
| `\ue10c` | master | 포켓몬 마스터 |
| `\ue10d` | collector | 전설의 수집가 |
| `\ue10e` | gym_champion | 체육관 챔피언 |
| `\ue10f` | christmas | 크리스마스 요정 |
| `\ue110` | sakura | 벚꽃의 여행자 |
| `\ue111` | donor | 후원자 |
| `\ue112` | legend_warrior | 전설의 전사 |
| `\ue113` | hero | 서버의 영웅 |
| `\ue114` | staff | Poki 스태프 |

## 배포

main 에 올리면 GitHub Actions 가 zip 으로 묶고, `VERSION` 이 새로우면 Releases 에 올린다.
릴리스 설명에 `server.properties` 에 넣을 `resource-pack` 주소와 `resource-pack-sha1` 이 적혀 있다.
(또는 런처가 `resourcepacks` 폴더에 넣어 배포)

## 주의

- 마크 글꼴 텍스처는 한 장이 256px 이하여야 함 (넘으면 안 보임). 지금 배지는 가로 최대 249px.
- 배지 크기는 `default.json` 의 `height` (글자 높이, 보통 글씨는 8). 게임 안에서 더 크게/작게는 PokiName config 의 `title.scale`.

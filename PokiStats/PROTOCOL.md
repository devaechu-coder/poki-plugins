# PokiStats → 통계 서버 전송 형식 (v1)

게임 서버(PokiStats 플러그인)가 **밖으로만** 보낸다. 통계 서버가 게임 서버로 연결하는 일은 없음.

## 요청

```
POST {endpoint}
Content-Type: application/json; charset=utf-8
Authorization: Bearer {INGEST_SECRET}
User-Agent: PokiStats/1.0.0
```

```json
{
  "server": "poki",
  "sentAt": 1761200000000,
  "plugin": "PokiStats/1.0.0",
  "events": [ { "id": "…", "t": "online", "at": 1761199700000, "…": "…" } ]
}
```

- 한 요청에 이벤트 최대 500개. 밀린 게 있으면 연달아 여러 번 보냄
- **응답 2xx = 전부 받았음** → 플러그인이 지움. 그 외(401, 500, 시간 초과)는 지우지 않고 다음 주기에 **같은 이벤트를 다시** 보냄
  → 통계 서버는 `id` 로 중복을 무시해야 함 (`INSERT OR IGNORE`)
- secret 이 틀리면 401. 형식이 이상한 이벤트 하나 때문에 전체를 거절하지 말 것 (거절하면 같은 묶음이 계속 재전송됨) → 모르는 `t` 나 깨진 이벤트는 건너뛰고 2xx
- 시각은 전부 epoch ms (UTC). 집계는 **KST(Asia/Seoul)** 기준으로

## 이벤트 공통

| 필드 | 설명 |
| --- | --- |
| `id` | 고유값 (UUID, 후원만 `donate-{장부번호}-{상태}`) — 중복 제거 키 |
| `t` | 종류 |
| `at` | 일어난 시각 (ms) |

## 이벤트 종류 (1단계)

### `boot` — 서버 켜짐
`version`, `maxPlayers`
→ 이 시각 전에 열려 있던 접속(quit 없이 끝난 것)은 마지막 `online` 기록 시각에 끝난 걸로 닫기 (서버가 튕긴 경우)

### `shutdown` — 정상 종료
(필드 없음. 바로 앞에 접속 중이던 사람들의 `quit`(reason `shutdown`)가 먼저 옴)

### `online` — 동접 샘플 (기본 5분마다)
`count` 접속자 수, `max` 최대 인원, `players` 접속 중인 uuid 배열
→ 시간대별 동접 그래프, 최고 동접. `players` 로 quit 이 빠진 세션 보정 가능

### `join` — 접속
`uuid`, `name`(마크 닉), `nick`(서버 닉네임·한글닉, 없으면 `""`), `first`(이 서버에 처음 들어옴), `totalPlayMin`(마크 누적 플레이 분, 플러그인 넣기 전 포함, 못 읽으면 -1)

### `quit` — 퇴장
`uuid`, `name`, `nick`, `sessionSec`(이번 접속 시간), `totalPlayMin`, `reason`(`quit` / `shutdown`)

### `donate` — 후원 (PokiDonate 장부)
`donationId`(장부 번호), `amount`(원), `status`, `note`, `type`(CHAT/VIDEO), `test`, `channelId`, `streamerUuid`, `streamerName`
- `at` 은 후원 받은 시각
- `status`: `PAID` 지급됨 · `FAILED` 실패 · `SKIPPED` 대상 아님 · `MANUAL` 수동 (대기 중인 건 안 보냄)
- 같은 장부 번호가 상태가 바뀌면 **다시 옴** → `donationId` 로 덮어쓰기(UPSERT)
- `note` 예: `입주민 10,000원 메뉴 · 3개` → 어느 메뉴(보상)가 나갔는지
- 후원자 닉네임·메시지는 보내지 않음

## 2단계 (PokiStats 1.1.0 + PokiEvents 모드)

통계 서버 요구사항 문서(프로젝트 `pokistats-plugin-events.md`) 그대로.

| t | 필드 |
| --- | --- |
| `capture` | `uuid, species, level, shiny, legendary, mythical, ultraBeast, ball` |
| `battle` | `uuid, kind`(wild/npc/pvp)`, result`(win/lose/flee) |
| `evolve` | `uuid, from, to, shiny` |
| `hatch` | `uuid, species, shiny` |
| `gym` | `uuid, trainerId`(kanto_brock … kanto_league_lance, kanto_champion_blue)`, result`(win/lose/quit) |
| `tower` | `uuid, floor, streak, result`(win/lose/forfeit) |
| `raid` | `uuid, boss, result`(win/lose) |
| `rank_hide` | `uuid, name, nick, hidden` — 접속할 때 + 숨김 상태가 바뀔 때 (오프라인 포함) |
| `nick` | `uuid, name, nick` — 서버 닉네임(PokiName 한글닉)을 정하거나 바꾸거나 지울 때(`""`), 1분 안에. 접속할 때는 `join` 의 `nick` 으로 대신 |
| `wealth` | `players: [{uuid, name, nick, money, coin}]` — money = 소지금($), coin = 코블달러(C). 10분마다 + 종료 시, 접속자 + 소지금·코블달러 상위 100 |

모르는 `t` 는 저장만 하거나 무시하고 2xx 로 받아주기.

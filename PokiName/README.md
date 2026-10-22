# PokiName

포키 서버용 한글닉 플러그인. 머리 위 이름표, Tab 목록 이름, Tab 위 환영 문구를 처리한다.

TAB 플러그인이 아크라이트에서 모드 차원 이동(레이드 텔포 등)을 막아서 대신 만든 것.
**패킷을 건드리지 않고 Bukkit 기본 API만 쓴다.**

## 빌드

```
mvn package
```

또는 GitHub Releases 에서 jar 받기.

`target/PokiName-<버전>.jar` 를 서버 `plugins` 폴더에 넣는다. (Java 21, Spigot API 1.21.1)

## 명령어 (OP)

| 명령어 | 설명 |
| --- | --- |
| `/한글닉 설정 <마크닉> <한글닉>` | 한글닉 지정 (접속 중인 플레이어만) |
| `/한글닉 해제 <마크닉>` | 한글닉 지우기 |
| `/한글닉 목록` | 등록된 한글닉 보기 |
| `/한글닉 리로드` | config.yml 다시 불러오기 |

영어로는 `/pnick set|remove|list|reload`. 권한: `pokiname.admin` (기본 OP)

## 칭호

머리 위 한글닉 위에 배지 이미지가 뜨고, Tab 목록·채팅 이름 앞에는 색 글씨 칭호가 붙는다.
배지 이미지는 [PokiTitles-RP](../PokiTitles-RP/) 리소스팩이 있어야 보인다 (없으면 네모 글자로 보임).

| 명령어 | 설명 |
| --- | --- |
| `/칭호` | 내 칭호 목록 — 글씨를 누르면 장착 |
| `/칭호 장착 <id>` / `/칭호 해제` | 칭호 장착 / 빼기 |
| `/칭호 지급 <마크닉> <id\|전체>` | 칭호 주기 (접속 안 해도 됨, 처음 받은 칭호면 자동 장착) |
| `/칭호 회수 <마크닉> <id\|전체>` | 칭호 뺏기 |
| `/칭호 보기 <마크닉>` | 가진 칭호 보기 |
| `/칭호 전체` | 칭호 id 목록 |
| `/칭호 리로드` | titles.yml 다시 불러오기 |

영어로는 `/ptitle`. 권한: `pokiname.title` (모두, 보기·장착), `pokiname.title.admin` (OP, 지급·회수·리로드)

- 지급은 콘솔에서도 됨 → 다른 플러그인 보상(후원 보상, 체육관 클리어 등)에서 `칭호 지급 {player} donor` 식으로 연결
- 칭호 목록: `plugins/PokiName/titles.yml` (id, 이름, 배지 글자 코드, Tab·채팅 글씨, 등급)
- 가진 칭호: `plugins/PokiName/player-titles.yml`
- 설정: config.yml 의 `title` (배지 높이 `gap`, 크기 `scale`, 끄기 `overhead`), `tab-name` / `display-name` 의 `{title}`
- 배지는 이름표와 같은 방식 (텍스트 디스플레이, 패킷 미사용, 본인 화면엔 안 보임)

### 새 칭호 추가

1. 배지 PNG 를 `PokiTitles-RP/assets/poki/textures/font/titles/` 에 넣기 (가로·세로 256px 이하)
2. `PokiTitles-RP/assets/minecraft/font/default.json` 에 글자 코드(다음 번호, 예: `\ue115`) 추가, `VERSION` 올리기
3. `titles.yml` 에 같은 `glyph` 로 한 줄 추가 → `/칭호 리로드`

## 동작 방식

- **머리 위 (`nametag.mode: display`, 기본)**: 스코어보드 팀으로 원래 이름표를 숨기고, 텍스트 디스플레이 엔티티를 매 틱 머리 위로 옮긴다. 본인에게는 `hideEntity` 로 안 보이게 한다.
  - 접속·차원 이동 직후 `spawn-delay-ticks`(기본 1초) 동안은 띄우지 않음 (로딩 중 잔상 방지)
  - 8칸 넘게 순간이동하면 옮기지 않고 새로 만듦
  - 5초마다 주인 없는 이름표 엔티티 정리
  - `see-through` 기본 false (셰이더 + 비 올 때 가려지는 문제)
- **머리 위 (`nametag.mode: prefix`)**: 팀 접두사로 "한글닉 마크닉" 형태
- **Tab 목록**: `setPlayerListName`
- **Tab 위/아래 문구**: `setPlayerListHeaderFooter`, 접속·퇴장 때 갱신

## 다른 플러그인에서 쓰기 (PlaceholderAPI)

PlaceholderAPI 가 있으면 자동 연결. 홀로그램(DecentHolograms)·스코어보드·Skript 등에서 사용.

| placeholder | 내용 |
| --- | --- |
| `%pokiname_nick%` | 그 플레이어의 한글닉 (없으면 마크닉) |
| `%pokiname_nick_<마크닉>%` | 마크닉으로 한글닉 찾기 — 접속 안 해도 됨 |
| `%pokiname_money_<n>%` | 소지금 n등 한 줄 (`money-rank.line` 형식) |
| `%pokiname_money_<n>_nick%` / `_name` / `_value` / `_raw` | n등 한글닉 / 마크닉 / `$7,199` / 숫자 |
| `%pokiname_cobble_<n>%` (+ `_nick` 등) | 코블달러 n등 (`92,415C`) |
| `%pokiname_biome%` | 지금 바이옴 한글 이름 (`난초 늪`), 없으면 `Orchid Swamp` 식 — `biomes.yml` 에서 수정 |
| `%pokiname_biome_1%` / `_2%` | 긴 바이옴 이름 두 줄로 (`biome.max-length`) |
| `%pokiname_title%` / `%pokiname_title_name%` | 장착한 칭호 색 글씨 / 이름만 |

### 코블달러 순위

CobbleDollars 모드가 `<월드>/cobbledollarsplayerdata/<uuid>.json` 에 저장한 값 + 접속 중인 사람은 모드에서 실시간 값(리플렉션).
파일은 바뀐 것만 다시 읽음. 설정은 `cobble-rank`. 겜블 모드 순위판(`/gpleaderboard`) 대신 씀.

### 소지금 순위

Vault(Essentials 돈)로 `interval-seconds`(기본 10초)마다 직접 계산. Essentials `baltop` 은 누가 `/baltop` 을 쳐야 갱신돼서 대신 만듦.
돈 1 미만은 빼고, 금액은 `$7,199` 처럼 쉼표·소수점 버림. 설정은 config.yml 의 `money-rank`.
1등이 바뀌면 `on-first-change` 명령어를 콘솔로 실행 (기본: Citizens 7번 NPC 스킨을 1등 스킨으로).

## 파일

- `plugins/PokiName/config.yml` — 문구·색·이름표 설정
- `plugins/PokiName/nicknames.yml` — 저장된 한글닉 (UUID 기준)
- `plugins/PokiName/titles.yml` — 칭호 목록
- `plugins/PokiName/player-titles.yml` — 플레이어별 가진 칭호 / 장착 칭호

## 빌드 자동화

main 에 올리면 GitHub Actions 가 빌드하고, pom.xml 버전이 새로우면 Releases 에 jar 를 올린다.

## 버전

- 1.5.0 — 칭호 (머리 위 배지 + Tab·채팅 색 글씨, `/칭호`, `%pokiname_title%`)
- 1.4.0 — 바이옴 한글 이름 (`%pokiname_biome%`), 긴 이름 두 줄 나누기
- 1.3.0 — 코블달러 순위 추가, 순위 공통 코드 정리
- 1.2.2 — 순위 제외를 한글닉으로도 가능, 켜질 때 순위 설정을 콘솔에 표시
- 1.2.1 — 1등이 바뀌면 명령어 실행 (`on-first-change`, 1등 NPC 스킨 자동 변경)
- 1.2.0 — PlaceholderAPI 연결(한글닉 조회), 소지금 순위 실시간 계산
- 1.1.2 — 벽 너머 보이기 기본 끔, 높이 0.6
- 1.1.1 — 이름표 잔상 수정
- 1.1.0 — 한글닉만 보이는 display 모드
- 1.0.0 — 팀 접두사 이름표, Tab 이름, 환영 문구

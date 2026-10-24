# PokiStats

포키 운영 통계 + 명예의 전당 플러그인. 운영진 전용 통계 화면에 들어갈 숫자를 몇 분마다 통계 서버로 보내고,
같은 기록으로 인게임 순위(`/명예의전당`)를 보여준다.

- **동접자 추이**: 5분마다 접속자 수
- **플레이 기록**: 접속·퇴장 (신규 여부, 접속 시간, 누적 플레이 시간) → 신규 유저 수, 플레이타임 순위, 재접속률
- **후원 통계**: PokiDonate 장부를 읽기만 해서 금액·상태·메뉴 → 금액별 보상 횟수, 스트리머별 합계

- **게임 이벤트** (2단계, [PokiEvents](https://github.com/devaechu-coder/poki-mods/tree/main/PokiEvents) 모드 필요): 포획·배틀·진화·부화·관장·배틀타워·레이드
- **운영진 숨김** (`rank_hide`) · **돈 스냅샷** (`wealth`: 소지금 $ · 코블달러 C, 10분마다)

서버 → 통계 서버로 **보내기만** 함. 밖에서 마크 서버로 들어오는 연결이 없어서 집 IP 가 공개되지 않음.
보내는 형식은 [PROTOCOL.md](PROTOCOL.md).

## 설치

1. `PokiStats-x.y.z.jar` 를 `plugins` 에 넣고 서버 켜기
2. 통계 서버 주소·키는 **jar 에 같이 빌드됨** — GitHub 레포 Settings → Secrets and variables → Actions 에
   `STATS_ENDPOINT`(받는 주소), `STATS_SECRET`(통계 서버 INGEST_SECRET 과 같은 값) 를 넣고 다시 빌드
   - 서버 `config.yml` 의 `endpoint`/`secret` 은 비워두면 jar 값을 씀 (직접 적으면 그게 우선)
   - 레포 코드에는 안 남지만 **Releases 의 jar 를 열면 보이니** jar 파일은 공유하지 말기. 키가 새면 두 곳 다 바꾸고 다시 빌드
3. `/통계` 로 "설정됨 (jar 기본값)" 확인

`endpoint` 가 비어 있어도 기록은 `queue.jsonl` 에 계속 쌓임 → 통계 서버가 나중에 생겨도 오픈 첫날부터 기록이 남음 (최대 `max-queue` 개).

## 명령어 (OP)

| 명령어 | 설명 |
| --- | --- |
| `/통계` | 통계 서버 설정 여부, 못 보낸 기록 수, 마지막 전송 결과 |
| `/통계 보내기` | 지금 바로 보내기 |
| `/통계 리로드` | config.yml 다시 읽기 |

## 명예의 전당

통계 서버(웹 명예의 전당)와 같은 이벤트·같은 기준이라 숫자가 같음 (차이는 전송 주기만큼 늦는 것뿐).

| 명령어 | 누가 | 설명 |
| --- | --- | --- |
| `/명예의전당` (`/포키순위`) | 모두 | 순위 15종 GUI → 누르면 TOP 10 + 내 순위, 전체/이번 주 전환 |
| `/명예의전당 포획왕 [주간]` | 모두 | 그 순위 바로 열기 |
| `/명예의전당 홀로그램 설치 <순위종류> [주간]` | OP | 서 있는 자리에 TOP 3 홀로그램 (config `hologram.size` 줄 수, `hologram.height` 발에서 몇 칸 위가 맨 아랫줄 — 0 이면 발 위치. 1분마다 갱신, 위치는 holograms.yml) |
| `/명예의전당 홀로그램 목록` · `삭제 <번호>` · `이동 <번호>` | OP | 홀로그램 관리 |
| `/명예의전당 초기화` → 30초 안에 `/명예의전당 초기화 확인` | OP | 시즌 초기화 (포획·배틀 등 기록 삭제, 돈 순위는 그대로). 통계 서버 쪽은 운영 통계에서 따로 지우기 |
| `/명예의전당 새로고침` | OP | 지금 바로 다시 계산 |

순위: 포획왕 · 도감왕 · 이로치 사냥꾼 · 전설 수집가 · 배틀왕 · PvP 강자(승률) · 최장 연승 · 진화 마스터 · 부화 장인 · 배틀 타워 최고층 · 레이드 영웅 · 체육관 정복 · 마스터볼 큰손 · 소지금 · 코블달러

- 이번 주 = 한국시간 월요일 0시부터. 같은 값이면 먼저 도달한 사람이 위
- **운영진 숨기기**: 권한 `pokistats.rank.hidden` (LuckPerms 로 운영진 그룹에) 또는 config `rank.hiddenPlayers` 에 마크닉/uuid, 또는 **PokiName 순위 제외 목록**(`rank-exclude` 등, 한글닉도 됨 — `rank.use-pokiname-exclude: true` 기본). OP 라도 자동으로는 안 숨겨짐. 기록은 쌓이고 표시에서만 빠짐, 웹 순위에서도 빠짐 (목록을 바꾸면 1분 안에 웹에도 알림 — 오프라인 계정도)
- 한글닉: PokiName 이 있으면 한글닉으로 표시

### PlaceholderAPI

`%pokistats_<종류>_<n>%` (n등 한 줄), `_<n>_name`, `_<n>_value`, 이번 주는 `%pokistats_<종류>_week_<n>%`, 내 순위 `%pokistats_<종류>_me%` · `_me_value`.
`<종류>`: capture dex shiny legend battle pvp streak evolve hatch tower raid gym masterball money cobble (한글 `포획왕` 등도 됨)

## 파일

- `queue.jsonl` — 아직 못 보낸 기록 (보내면 지워짐)
- `donate-state.yml` — 후원 장부에서 어디까지 보냈는지
- `inbox/` — PokiEvents 모드가 남긴 게임 이벤트 (읽으면 지워짐)
- `ranks.db` — 인게임 순위 기록 (SQLite)
- `holograms.yml` — 로비 홀로그램 위치

## 버전

- 1.1.6 — 서버 닉네임(한글닉)을 웹으로: join·quit·rank_hide·wealth 에 nick, 닉을 정하거나 바꾸거나 지우면 1분 안에 nick 이벤트 (nick-sent.yml)
- 1.1.5 — 운영진 숨김이 바뀌면 접속 안 해도 1분 안에 웹(rank_hide)에 알림 (오프라인 계정 포함, hidden-sent.yml)
- 1.1.4 — 홀로그램 글자를 config `hologram.format` 으로 직접 정하기 (제목·등수 줄·빈 줄·아래 줄, 순위별 `hologram.formats.<종류>` 덮어쓰기)
- 1.1.3 — 명예의 전당 홀로그램을 로비 소지금 판 모양으로 (✦ 포키 ○○ 랭킹 ✦ · 메달 ①②③ · ◆ · 구분선 · 문구 `hologram.footer`), 기본 TOP 10, 배경 없음
- 1.1.2 — 홀로그램 높이 설정 `hologram.height` (0 = 발 위치가 맨 아랫줄)
- 1.1.1 — 운영진 숨김에 PokiName 순위 제외 목록도 같이 씀 (한글닉으로 적어도 됨), 순위 계산 때마다 접속자 숨김 상태 갱신
- 1.1.0 — 게임 이벤트 전달(PokiEvents), rank_hide · wealth, 명예의 전당 GUI · 로비 홀로그램 · PAPI
- 1.0.0 — 동접 · 접속 기록 · 후원 통계 (1단계)

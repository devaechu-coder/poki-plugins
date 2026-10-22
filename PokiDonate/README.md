# PokiDonate

치지직 후원 연동. 스트리머가 치지직 채널을 연결하면, 방송 후원이 들어올 때마다 서버 장부에 기록한다.
치지직 **공식 Open API**(세션 + 후원 이벤트)로 받으니까 채팅을 가로채지 않고, 채널ID를 손으로 맞출 필요도 없다.

> 1.1.0: 연동 + 후원 수신 + 장부 기록 + **입주민 보상 지급(포키 롤리팝)**. 운영진 서버 이벤트는 보상표만 비워둔 상태.

## 명령어

| 명령어 | 하는 일 | 권한 |
| --- | --- | --- |
| `/후원` | 도움말 | 누구나 |
| `/후원 연동` | 치지직 연동 링크 받기 (10분 유효) | poki.donate.streamer |
| `/후원 해제 확인` | 내 연동 해제 (기록은 남음) | poki.donate.streamer |
| `/후원 기록 [페이지]` | 내 방송 후원 기록 | poki.donate.streamer |
| `/후원 보상표` | 금액별 보상 보기 | poki.donate.streamer |
| `/후원 기록 <이름\|전체> [페이지]` | 스트리머별·전체 기록 | poki.donate.admin |
| `/후원 현황` | 연동된 스트리머, 받는 중인지, 토큰 상태 | poki.donate.admin |
| `/후원 강제해제 <이름>` | 연동 강제 해제 | poki.donate.admin |
| `/후원 테스트 <이름> <금액> [메시지]` | 가짜 후원 기록 + 보상 지급 (테스트 표시, 대상이 접속 중일 때만) | poki.donate.admin |
| `/후원 미지급 [페이지]` | 대기·실패한 보상 모아 보기 | poki.donate.admin |
| `/후원 재지급 <번호>` | 그 후원 보상 다시 지급 | poki.donate.admin |
| `/후원 리로드` | config.yml · rewards.yml 다시 읽고 치지직 재연결 | poki.donate.admin |

영어 명령어도 된다: `/donate link|unlink|log|status|forceunlink|test|reload`

## 권한 (LuckPerms)

| 그룹 | 줄 권한 |
| --- | --- |
| 입주민 | `poki.donate.streamer` |
| 운영진 | `poki.donate.staff` (입주민 권한 포함, 다음 단계에서 서버 전체 이벤트용) |
| 관리자 | `poki.donate.admin` (streamer 포함, OP 기본) |

```
/lp group 입주민 permission set poki.donate.streamer true
/lp group 운영진 permission set poki.donate.staff true
/lp group 관리자 permission set poki.donate.admin true
```

## 처음 설치

1. 서버 `plugins` 폴더에 `PokiDonate-x.y.z.jar` 를 넣고 한 번 켠다 → `plugins/PokiDonate/config.yml` 생성
2. `config.yml` 에 비밀값 두 개를 넣는다
   - `chzzk.client-secret`: 치지직 개발자센터 → poki-donate 앱의 Client Secret
   - `bridge.secret`: GitHub 레포 Secrets 의 `POKI_BRIDGE_SECRET` 과 같은 값
3. `/후원 리로드` → 콘솔에 `치지직 세션 연결됨` 은 연동한 스트리머가 생겨야 뜬다
4. 입주민이 `/후원 연동` → 버튼 → 치지직에서 허용 → 몇 초 뒤 게임에 "연동 완료"
5. `/후원 테스트 <이름> 1000` 으로 장부에 들어가는지 확인, 실제 방송에서 소액 후원으로 한 번 더 확인

## 동작 방식

```
/후원 연동 → auth.poki.devaechu.com (donate-web) → 치지직 권한 허용
         → 인증 페이지가 코드 보관 → 플러그인이 3초마다 가져감 → 토큰 발급·채널 확인·저장
치지직 세션 서버 ──후원 이벤트──▶ 플러그인 ──▶ 장부(donate.db) 기록
```

- 스트리머가 그 순간 **마크에 접속 중일 때만** 기록한다 (`대기`). 마크에 없을 때 온 후원은 장부에 남기지 않는다
- 연동 안 된 채널의 후원은 `대상 아님 · 미연동 채널`

- 클라이언트 세션 하나에 스트리머 25명씩 묶어서 구독한다 (치지직 한도: 세션당 구독 30개, 연결 10개)
- 연결이 끊기면 5초→10초→…최대 2분 간격으로 새 세션을 받아 다시 붙고 구독도 다시 한다
- access token(1일)은 30분마다 점검해서 만료 3시간 전에 갱신한다. refresh token 은 일회용이라 갱신 즉시 저장
- 스트리머가 치지직에서 권한을 철회하거나 토큰이 죽으면 "재연동 필요" 로 바뀌고 본인·관리자에게 알림
- 미션 후원은 공식 API 로 오지 않는다 (치지직 제한)
- 끊겨 있던 동안의 후원은 다시 받을 방법이 없다. 끊김·재접속 시각은 `session_log` 테이블에 남는다

## 보상표 (`plugins/PokiDonate/rewards.yml`)

후원 메뉴판. 고친 뒤 `/후원 리로드` 하면 바로 적용 (jar 교체·재시작 필요 없음).

- `match: exact` (기본) — 메뉴 금액과 **딱 맞을 때만** 지급. 메뉴에 없는 금액은 `대상 아님 · 메뉴에 없는 금액`
- `match: at-least` — 그 금액 이상 중 가장 높은 칸 하나
- `resident`(입주민) / `staff`(운영진, `poki.donate.staff`) 두 표. 한 후원에 표 하나만 적용
- 칸: `amount`(메뉴 금액), `name`(/후원 보상표 표시), `message`, `commands`(콘솔 실행)
- `commands: []` 로 비워두면 그 메뉴는 `실패 · 보상 명령어 미설정` + 관리자 알림 → 직접 지급 후 처리
- 치환: `%player%`(스트리머 마크 닉) `%count%` `%amount%` `%donator%`
- 특수 명령어 `@wipe-and-kill` — `keep-items` 에 적은 필수 아이템만 남기고 인벤토리(갑옷·왼손 포함) 전부 삭제 후 즉사. 남긴 아이템은 부활하면 돌려줌
- `keep-items` — 아이템 id(`cobblemon:pokedex_*` 처럼 `*` 가능) 또는 `cmd:번호`(custom_model_data)
- give 는 꼭 `minecraft:give` (그냥 `give` 는 Essentials 가 가로채서 아이템 컴포넌트가 깨짐)
- 포키 롤리팝 = 데이터팩 함수 `poki:give_lollipop` (custom_model_data 1001)

지급 흐름: 후원 기록 → 그 자리에서 지급 → `지급됨`. 지급 직전에 스트리머가 나갔으면 `대기` 로 두고 다음 접속 때 자동 지급.
명령어 실행이 실패하면 `실패` + 관리자 알림 → `/후원 재지급 <번호>`.

## 장부 (`plugins/PokiDonate/donate.db`, SQLite)

| 테이블 | 담는 것 |
| --- | --- |
| `chzzk_link` | 마크 계정 ↔ 치지직 채널, 토큰 |
| `donation` | 후원 한 건씩. 장부 번호(id)는 받는 순간 붙인다 (치지직 이벤트엔 고유 번호가 없음) |
| `session_log` | 연결·끊김·구독 오류·연동·해제 기록 |

SQLite 드라이버는 서버에 들어있는 것(CoreProtect 가 쓰는 것과 같은 것)을 쓴다. 백업은 파일 복사.
토큰이 들어있으니 `donate.db` 는 공유하지 말 것.

## 문제 생겼을 때

- `config.yml` 에서 `debug: true` → `/후원 리로드` 하면 치지직에서 오는 원본 이벤트가 콘솔에 찍힌다
- `인증 페이지 응답 401` → `bridge.secret` 이 GitHub Secret `POKI_BRIDGE_SECRET` 과 다름
- `/후원 현황` 에서 "재연동 필요" → 그 스트리머가 `/후원 연동` 다시

## 빌드

`mvn package`

## 버전 기록

- 1.2.0 — 메뉴판 방식(딱 그 금액일 때 지급), 칸 이름, 명령어 미설정 칸은 실패+관리자 알림, 즉사(@wipe-and-kill, 필수 아이템 보존), 롤리팝 데이터팩 함수
- 1.1.0 — 보상 지급 (rewards.yml, 포키 롤리팝), /후원 보상표 · 미지급 · 재지급, 접속 시 밀린 보상 자동 지급
- 1.0.0 — 치지직 공식 API 연동, 후원 수신, 장부 기록, 관리 명령어. 마크 미접속 중 후원은 기록 안 함, 운영진 권한 하나로 통합

# PokiDonate

치지직 후원 연동. 스트리머가 치지직 채널을 연결하면, 방송 후원이 들어올 때마다 서버 장부에 기록한다.
치지직 **공식 Open API**(세션 + 후원 이벤트)로 받으니까 채팅을 가로채지 않고, 채널ID를 손으로 맞출 필요도 없다.

> 지금은 1단계: **연동 + 후원 수신 + 장부 기록**까지. 보상 지급(입주민 보상표, 운영진 서버 이벤트)은 다음 단계.

## 명령어

| 명령어 | 하는 일 | 권한 |
| --- | --- | --- |
| `/후원` | 도움말 | 누구나 |
| `/후원 연동` | 치지직 연동 링크 받기 (10분 유효) | poki.donate.streamer |
| `/후원 해제 확인` | 내 연동 해제 (기록은 남음) | poki.donate.streamer |
| `/후원 기록 [페이지]` | 내 방송 후원 기록 | poki.donate.streamer |
| `/후원 기록 <이름\|전체> [페이지]` | 스트리머별·전체 기록 | poki.donate.admin |
| `/후원 현황` | 연동된 스트리머, 받는 중인지, 토큰 상태 | poki.donate.admin |
| `/후원 강제해제 <이름>` | 연동 강제 해제 | poki.donate.admin |
| `/후원 테스트 <이름> <금액> [메시지]` | 가짜 후원을 장부에 기록 (테스트 표시) | poki.donate.admin |
| `/후원 리로드` | config.yml 다시 읽고 치지직 재연결 | poki.donate.admin |

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

- 스트리머가 그 순간 **마크에 접속 중**이면 `대기`(보상 대상), 아니면 `대상 아님 · 마크 미접속` 으로 남는다
- 연동 안 된 채널의 후원은 `대상 아님 · 미연동 채널`

- 클라이언트 세션 하나에 스트리머 25명씩 묶어서 구독한다 (치지직 한도: 세션당 구독 30개, 연결 10개)
- 연결이 끊기면 5초→10초→…최대 2분 간격으로 새 세션을 받아 다시 붙고 구독도 다시 한다
- access token(1일)은 30분마다 점검해서 만료 3시간 전에 갱신한다. refresh token 은 일회용이라 갱신 즉시 저장
- 스트리머가 치지직에서 권한을 철회하거나 토큰이 죽으면 "재연동 필요" 로 바뀌고 본인·관리자에게 알림
- 미션 후원은 공식 API 로 오지 않는다 (치지직 제한)
- 끊겨 있던 동안의 후원은 다시 받을 방법이 없다. 끊김·재접속 시각은 `session_log` 테이블에 남는다

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

- 1.0.0 — 치지직 공식 API 연동, 후원 수신, 장부 기록, 관리 명령어. 마크 미접속 중 후원은 보상 대상 아님, 운영진 권한 하나로 통합

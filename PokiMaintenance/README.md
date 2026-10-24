# PokiMaintenance

포키 서버 점검 모드. 점검 중엔 운영진만 접속할 수 있다.

## 명령어 (OP / `poki.maintenance.admin`)

| 명령어 | 설명 |
| --- | --- |
| `/점검 시작 [종료 예정]` | 점검 시작. 예: `/점검 시작 22:00`, `/점검 시작 오늘 밤 10시`. 접속 중인 일반 유저는 알림 후 10초 뒤 내보냄 |
| `/점검 끝` | 점검 끝, 다시 모두 접속 가능 |
| `/점검 상태` | 지금 상태 |
| `/점검 리로드` | config.yml 다시 불러오기 |

## 운영진 지정

- LuckPerms 운영진 그룹에 `poki.maintenance.bypass` 권한 주기 (추천): `/lp group 운영진 permission set poki.maintenance.bypass true`
- 또는 config.yml 의 `staff` 에 마크닉 적기
- OP 는 항상 통과

## 동작

- 점검 상태는 `plugins/PokiMaintenance/state.yml` 에 저장 → 서버를 다시 켜도 유지
- 점검 중 서버 목록 문구가 `[점검 중] 포키 서버 / 22:00 종료 예정` 으로 바뀜
- 포키 런처(1.0.8~)는 이 문구의 `[점검 중]` 을 보고 "점검 중" 표시 + 시작 버튼을 바꿈 → **motd 의 `[점검 중]` 글자는 지우지 말 것**

## 버전

- 1.0.0 — 점검 모드, 한글 안내, 서버 목록 문구, 상태 저장

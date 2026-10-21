# PokiName

포키 서버용 한글닉 플러그인. 머리 위 이름표, Tab 목록 이름, Tab 위 환영 문구를 처리한다.

TAB 플러그인이 아크라이트에서 모드 차원 이동(레이드 텔포 등)을 막아서 대신 만든 것.
**패킷을 건드리지 않고 Bukkit 기본 API만 쓴다.**

## 빌드

```
mvn package
```

`target/PokiName-<버전>.jar` 를 서버 `plugins` 폴더에 넣는다. (Java 21, Spigot API 1.21.1)

## 명령어 (OP)

| 명령어 | 설명 |
| --- | --- |
| `/한글닉 설정 <마크닉> <한글닉>` | 한글닉 지정 (접속 중인 플레이어만) |
| `/한글닉 해제 <마크닉>` | 한글닉 지우기 |
| `/한글닉 목록` | 등록된 한글닉 보기 |
| `/한글닉 리로드` | config.yml 다시 불러오기 |

영어로는 `/pnick set|remove|list|reload`. 권한: `pokiname.admin` (기본 OP)

## 동작 방식

- **머리 위 (`nametag.mode: display`, 기본)**: 스코어보드 팀으로 원래 이름표를 숨기고, 텍스트 디스플레이 엔티티를 매 틱 머리 위로 옮긴다. 본인에게는 `hideEntity` 로 안 보이게 한다.
  - 접속·차원 이동 직후 `spawn-delay-ticks`(기본 1초) 동안은 띄우지 않음 (로딩 중 잔상 방지)
  - 8칸 넘게 순간이동하면 옮기지 않고 새로 만듦
  - 5초마다 주인 없는 이름표 엔티티 정리
  - `see-through` 기본 false (셰이더 + 비 올 때 가려지는 문제)
- **머리 위 (`nametag.mode: prefix`)**: 팀 접두사로 "한글닉 마크닉" 형태
- **Tab 목록**: `setPlayerListName`
- **Tab 위/아래 문구**: `setPlayerListHeaderFooter`, 접속·퇴장 때 갱신

## 파일

- `plugins/PokiName/config.yml` — 문구·색·이름표 설정
- `plugins/PokiName/nicknames.yml` — 저장된 한글닉 (UUID 기준)

## 버전

- 1.1.2 — 벽 너머 보이기 기본 끔, 높이 0.6
- 1.1.1 — 이름표 잔상 수정
- 1.1.0 — 한글닉만 보이는 display 모드
- 1.0.0 — 팀 접두사 이름표, Tab 이름, 환영 문구

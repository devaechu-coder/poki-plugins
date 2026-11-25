# PokiTown

포키 마을. 마을은 따로 저장하지 않고 **Towny 에서 바로 읽는다** (기존 마을 그대로).

- 머리 위 마을 표시(PokiName)·포키폰 마을 앱에 마을 정보를 넘겨줌
- 마을 표시 이름·색: `plugins/PokiTown/towns.yml` (마을 UUID 기준 → 마을 이름이 바뀌어도 유지)
- Towny 이벤트(가입·탈퇴·추방·삭제·이름 변경·이장 변경·직책·알바)를 받아서 바뀐 사람만 알림 → 주기 확인 없음
- 마을 이름이 욕설 필터(포키폰 SNS 와 같은 목록)에 걸리면 `filtered-name`(기본 "마을")으로 보여주고 운영진 알림

Towny **0.101.2.0** 으로 빌드. 서버 Towny 를 올리면 `pom.xml` 의 Towny 버전을 바꾸고 다시 빌드.

## 명령어 (OP, `pokitown.admin`)

| 명령어 | 설명 |
| --- | --- |
| `/마을앱 표시 <마을> <이름>` | 머리 위·포키폰에 보일 마을 이름 (Towny 이름은 안 바뀜) |
| `/마을앱 색 <마을> <색>` | 마을 이름 색 (`&b`, `#7EC8E3`) |
| `/마을앱 초기화 <마을>` | Towny 이름·기본색으로 |
| `/마을앱 정보 <마을>` | Towny 이름·보이는 이름·주민 수 |
| `/마을앱 목록공개 켜기\|끄기\|상태` | 포키폰 '전체 마을' 탭을 일반 유저에게도 보일지 (바로 적용, towns.yml 에 저장) |
| `/마을앱 리로드` | config.yml · towns.yml 다시 읽기 |

## 플레이스홀더

`%pokitown_name%` (표시 이름) · `%pokitown_color%` · `%pokitown_towny%` (Towny 이름) · `%pokitown_role%` (이장/부이장/주민)

## API (`kr.devaechu.pokitown.PokiTownApi`)

리플렉션으로 부르기 쉽게 JDK 타입만 씀. 메인 스레드에서.

| 메서드 | 설명 |
| --- | --- |
| `townOf(UUID)` | `{uuid, name, display, color, role, custom}` · 마을 없으면 null (알바는 마을 없음) |
| `onChange(Consumer<UUID>)` | 접속 중인 사람의 마을 소속·직책·알바·표시가 바뀌면 (Bukkit 이벤트 `PokiTownChangeEvent` 도 같이 발생) |
| `onNotify(BiConsumer<UUID,String>)` | 포키폰 알림 문구 (초대 받음, 주민 들어옴·나감, 부이장·알바 지정) |
| `me` · `towns` · `town` · `act` | 포키폰 마을 앱용. `act` 는 그 사람이 Towny 명령어를 친 것처럼 **본인 권한**으로 실행, `/confirm` 자동 |
| `listPublic()` · `setListPublic(UUID, boolean)` | 전체 마을 목록 공개 스위치 (OP 만) |

## 버전 기록

- **1.1.0** — 운영진이 포키폰 전체 마을 탭에서 아무 마을 스폰으로 이동 (act spawn_town, OP 만)
- **1.0.0** — 처음 만듦

# PokiUI

다른 플러그인이 코드에 박아 둔 상자 제목을, 창이 열린 직후 포키 UI 그림 제목(헤더 + 배경)으로 바꿔주는 플러그인.

처음 목적: SimpleShopJS 는 상점 제목이 코드에 `상점: <이름>` 으로 고정이라 설정으로 못 바꿈 → PokiUI 가 바꿔줌.
SimpleShopJS 는 클릭을 제목이 아니라 열린 창 기록으로 구분해서 제목을 바꿔도 구매·판매는 그대로 동작.

## 설치

`PokiUI-<버전>.jar` 를 `plugins` 에 넣고 재시작. 리소스팩 [PokiTitles-RP](../PokiTitles-RP/) 1.3.1 이상 필요.

## 설정 (`plugins/PokiUI/config.yml`)

```yaml
titles:
  "상점: 코블버스": "&f…"   # 원래 제목(색 코드 빼고) : 새 제목
```

- 기본으로 SimpleShopJS 상점 7개 (포켓몬·코블버스·건축·랜덤박스·농사·볼·광물) 가 들어 있음
- 원래 제목은 창을 연 채로 `/포키ui 제목` 을 치면 채팅에 나옴 (OP)
- 고친 뒤 `/포키ui 리로드`

## 동작

창이 열리면 다음 틱에 `InventoryView.setTitle` 로 제목만 다시 보냄 (한 순간 원래 제목이 보일 수 있음).

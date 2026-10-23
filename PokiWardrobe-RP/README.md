# PokiWardrobe-RP

PokiWardrobe 치장 모자 리소스팩. 마크 1.21.1 (pack_format 34). 미리보기: [preview.png](preview.png)

모자는 **종이(paper) 아이템 + custom_model_data** 로 바꿔 보여준다 (`assets/minecraft/models/item/paper.json`).

| 번호 | 모델 | 치장 (cosmetics.yml) |
| --- | --- | --- |
| 1101 | `poki:item/hats/poki_crown` | 왕관 |
| 1102 | `poki:item/hats/poki_pokeball_cap` | 몬스터볼_캡모자 |
| 1103 | `poki:item/hats/poki_ribbon` | 리본_머리띠 |
| 1104 | `poki:item/hats/poki_cat_ears` | 고양이_귀_머리띠 |
| 1105 | `poki:item/hats/poki_flower_crown` | 꽃_화관 |

다른 커스텀 아이템 번호: 1001 포키 롤리팝 (빵, 트레이너 스킨 팩 안). 같은 바닐라 아이템을 두 팩이 덮으면 위에 있는 팩만 먹으니까 **모자는 종이만** 쓴다.

## 모자 추가하기

블록벤치에서 `Java Block/Item` 으로 만들고 Display → Head 로 위치 맞춰서 `.json` + `.png` 내보내기.
- 파일 이름은 영어 소문자·숫자·`_` 만
- `assets/poki/models/item/hats/` 에 json, `assets/poki/textures/item/hats/` 에 png, json 의 텍스처 경로를 `poki:item/hats/<이름>` 으로
- `paper.json` 의 overrides 에 다음 번호(1106~) 추가, `VERSION` 올리기
- PokiWardrobe 의 cosmetics.yml 에 `material: PAPER`, `model: <번호>` 로 추가 → `/치장관리 리로드`

지금 5개는 `tools/build_hats.py`(상자 좌표) + `tools/build_hats_textured.py`(면마다 도트 텍스처: 하이라이트·그림자·체크무늬·털·잎) 로 만든 것. 모자마다 텍스처 한 장. 머리 칸에 씌우면 마크가 0.625 배로 줄여서 머리 가운데에 맞추니까,
모델 좌표로 머리는 1.6~14.4, 머리 꼭대기 y=14.4, 얼굴 쪽이 north(z 작은 쪽).

## 배포

main 에 올리면 Actions 가 zip 으로 묶어 Releases 에 올린다 (`VERSION` 이 새로울 때). 서버에는 resourcepacks-poki.zip 안에 넣어서 R2 에 올리기.

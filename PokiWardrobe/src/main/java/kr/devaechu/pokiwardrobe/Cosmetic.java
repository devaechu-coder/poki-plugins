package kr.devaechu.pokiwardrobe;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.List;

/**
 * 치장 하나 (cosmetics.yml 의 한 항목).
 *
 * @param id          명령어에 쓰는 이름 (띄어쓰기 대신 _)
 * @param name        보여줄 이름 (색 코드 포함)
 * @param category    카테고리
 * @param material    모자: 머리에 씌울 아이템 / 파티클: 옷장 아이콘
 * @param model       custom_model_data (0 이면 안 씀)
 * @param particle    파티클 종류 (파티클만)
 * @param dustColor   DUST 파티클 색 (파티클만, 없으면 null)
 * @param count       한 번에 뿌릴 파티클 수
 * @param description 옷장에 보일 설명
 * @param unlock      잠겨 있을 때 보일 얻는 방법
 */
record Cosmetic(String id, String name, Category category, Material material, int model,
                Particle particle, Color dustColor, int count, List<String> description, String unlock) {

    enum Category {
        HAT("모자"), PARTICLE("파티클"), OUTFIT("의상");

        final String label;

        Category(String label) {
            this.label = label;
        }

        static Category of(String s) {
            if (s == null) return null;
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "hat", "모자" -> HAT;
                case "particle", "파티클" -> PARTICLE;
                case "outfit", "의상" -> OUTFIT;
                default -> null;
            };
        }
    }
}

package kr.devaechu.pokistats.rank;

/**
 * 명예의 전당 순위 종류. 기준은 통계 서버(웹 명예의 전당)와 똑같이.
 * 값이 같으면 그 값에 먼저 도달한 사람이 위 (reached 가 작은 쪽).
 */
public enum Category {

    CAPTURE("capture", "포획왕", "마리", "cobblemon:poke_ball", "POKE_BALL;SLIME_BALL", Kind.COUNT,
            "t='capture'"),
    DEX("dex", "도감왕", "종", "cobblemon:pokedex_red", "BOOK", Kind.DISTINCT_SPECIES,
            "t IN ('capture','hatch','evolve')"),
    SHINY("shiny", "이로치 사냥꾼", "마리", "cobblemon:shiny_charm", "NETHER_STAR", Kind.COUNT,
            "t IN ('capture','hatch') AND shiny=1"),
    LEGEND("legend", "전설 수집가", "마리", "cobblemon:master_ball", "DRAGON_EGG", Kind.COUNT,
            "t='capture' AND (legendary=1 OR mythical=1)"),
    BATTLE("battle", "배틀왕", "승", "minecraft:iron_sword", "IRON_SWORD", Kind.COUNT,
            "t='battle' AND result='win'"),
    PVP("pvp", "PvP 강자", "승", "minecraft:diamond_sword", "DIAMOND_SWORD", Kind.COUNT,
            "t='battle' AND kind='pvp' AND result='win'"),
    STREAK("streak", "최장 연승", "연승", "minecraft:blaze_powder", "BLAZE_POWDER", Kind.STREAK, null),
    EVOLVE("evolve", "진화 마스터", "회", "cobblemon:fire_stone", "AMETHYST_SHARD", Kind.COUNT,
            "t='evolve'"),
    HATCH("hatch", "부화 장인", "개", "minecraft:egg", "EGG", Kind.COUNT,
            "t='hatch'"),
    TOWER("tower", "배틀 타워 최고층", "층", "minecraft:lodestone", "LODESTONE", Kind.MAX_FLOOR, null),
    RAID("raid", "레이드 영웅", "회", "minecraft:end_crystal", "END_CRYSTAL", Kind.COUNT,
            "t='raid' AND result='win'"),
    GYM("gym", "체육관 정복", "개", "minecraft:golden_helmet", "GOLDEN_HELMET", Kind.DISTINCT_TRAINER,
            "t='gym' AND result='win'"),
    MASTERBALL("masterball", "마스터볼 큰손", "개", "cobblemon:master_ball", "ENDER_EYE", Kind.COUNT,
            "t='capture' AND ball LIKE '%master_ball'"),
    MONEY("money", "소지금", "$", "minecraft:gold_ingot", "GOLD_INGOT", Kind.WEALTH, "money"),
    COBBLE("cobble", "코블달러", "C", "minecraft:emerald", "EMERALD", Kind.WEALTH, "coin");

    public enum Kind { COUNT, DISTINCT_SPECIES, DISTINCT_TRAINER, STREAK, MAX_FLOOR, WEALTH }

    public final String key;
    public final String title;
    public final String unit;
    /** 아이콘: 모드 아이템 id 먼저, 안 되면 바닐라 Material 후보(; 로 구분) */
    public final String iconId;
    public final String iconFallback;
    public final Kind kind;
    /** COUNT·DISTINCT 의 WHERE 조건, WEALTH 는 컬럼 이름 */
    public final String where;

    Category(String key, String title, String unit, String iconId, String iconFallback, Kind kind, String where) {
        this.key = key;
        this.title = title;
        this.unit = unit;
        this.iconId = iconId;
        this.iconFallback = iconFallback;
        this.kind = kind;
        this.where = where;
    }

    /** 주간 순위가 있는지 (돈은 지금 잔액만) */
    public boolean hasWeekly() {
        return kind != Kind.WEALTH;
    }

    /** 표시용 값 (소지금 $152,000 / 코블달러 340C / 120마리) */
    public String format(double v) {
        String n = String.format("%,d", (long) Math.floor(v));
        if (this == MONEY) return "$" + n;
        if (this == COBBLE) return n + "C";
        return n + unit;
    }

    /** 한글 이름·영문 key 둘 다로 찾기 (공백 무시) */
    public static Category find(String s) {
        if (s == null) return null;
        String q = s.replace(" ", "").trim();
        for (Category c : values()) {
            if (c.key.equalsIgnoreCase(q) || c.title.replace(" ", "").equalsIgnoreCase(q) || c.name().equalsIgnoreCase(q)) return c;
        }
        return null;
    }
}

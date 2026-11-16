package kr.devaechu.pokigacha;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 뽑기 한 종류 (예: 치장, 일반도구) */
final class Pool {
    final String name;
    String display;            // 뽑기권 이름 (색 코드 가능)
    ItemStack ticket;          // 뽑기권 생김새 (null 이면 종이)
    boolean noDuplicate;       // 이미 가진 치장은 안 나옴
    String fallbackPool;       // 전부 가졌을 때 대신 줄 뽑기권 종류 (null = 없음)
    int fallbackCount = 1;
    final List<Reward> rewards = new ArrayList<>();
    transient String editor;   // 지금 편집 중인 운영진 이름 (편집 중엔 못 뽑음)

    Pool(String name) {
        this.name = name;
        this.display = "&d" + name + " 뽑기권";
    }

    int totalWeight() {
        int t = 0;
        for (Reward r : rewards) t += Math.max(0, r.weight);
        return t;
    }

    Pool copy() {
        Pool p = new Pool(name);
        p.display = display;
        p.ticket = ticket == null ? null : ticket.clone();
        p.noDuplicate = noDuplicate;
        p.fallbackPool = fallbackPool;
        p.fallbackCount = fallbackCount;
        for (Reward r : rewards) p.rewards.add(r.copy());
        return p;
    }

    /** 보상 하나 */
    static final class Reward {
        ItemStack item;
        int weight = 1;   // 기본은 전부 같은 확률. 꼭 바꾸고 싶으면 gacha.yml 에서
        List<String> commands = new ArrayList<>(); // 같이 실행할 명령어 (%player%) — gacha.yml 에서만

        Reward copy() {
            Reward r = new Reward();
            r.item = item == null ? null : item.clone();
            r.weight = weight;
            r.commands = new ArrayList<>(commands);
            return r;
        }
    }
}

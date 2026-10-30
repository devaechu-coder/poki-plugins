package kr.devaechu.pokimascot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 포키냥 마스코트: 리소스팩 3D 모델(paper + custom_model_data)을 아이템 디스플레이로 세우고,
 * 같은 자리에 상호작용 엔티티를 둬서 우클릭하면 안내 메시지를 보여준다.
 * 두 엔티티는 월드에 저장되므로 서버를 껐다 켜도 그대로 있음. 둘은 PDC 의 같은 id 로 묶임.
 */
public final class PokiMascot extends JavaPlugin implements Listener {

    private NamespacedKey keyId, keyScale;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, Integer> step = new HashMap<>();
    private List<List<String>> messages = new ArrayList<>();
    private boolean bobUp = true;

    @Override
    public void onEnable() {
        keyId = new NamespacedKey(this, "id");
        keyScale = new NamespacedKey(this, "scale");
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        // 둥실둥실: ticks 마다 위/아래 목표로 보간
        int t = Math.max(5, getConfig().getInt("bob.ticks", 30));
        Bukkit.getScheduler().runTaskTimer(this, this::bob, 40L, t);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        reloadConfig();
        messages = new ArrayList<>();
        for (Object o : getConfig().getList("messages", List.of())) {
            List<String> group = new ArrayList<>();
            if (o instanceof List<?> l) for (Object s : l) group.add(String.valueOf(s));
            else group.add(String.valueOf(o));
            if (!group.isEmpty()) messages.add(group);
        }
    }

    private ItemStack modelItem() {
        Material m = Material.matchMaterial(getConfig().getString("model.item", "PAPER"));
        ItemStack it = new ItemStack(m == null ? Material.PAPER : m);
        ItemMeta meta = it.getItemMeta();
        meta.setCustomModelData(getConfig().getInt("model.custom-model-data", 8401));
        it.setItemMeta(meta);
        return it;
    }

    private Transformation shape(float scale, float lift) {
        // 모델 바닥(y 0)이 엔티티 위치에 오게: 아이템 모델은 가운데가 원점이라 0.5*크기 올림
        return new Transformation(new Vector3f(0, 0.5f * scale + lift, 0), new AxisAngle4f(),
                new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    private void spawn(Player p, float scale) {
        Location at = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        at.setYaw(Math.round(p.getLocation().getYaw() / 45f) * 45f);
        at.setPitch(0);
        String id = UUID.randomUUID().toString();
        ItemDisplay d = p.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(modelItem());
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTransformation(shape(scale, 0));
            e.setBillboard(Display.Billboard.FIXED);
            e.setPersistent(true);
            e.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, id);
            e.getPersistentDataContainer().set(keyScale, PersistentDataType.FLOAT, scale);
        });
        p.getWorld().spawn(at, Interaction.class, e -> {
            e.setInteractionWidth(1.1f * scale);
            e.setInteractionHeight(1.9f * scale);
            e.setResponsive(true);
            e.setPersistent(true);
            e.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, id);
        });
        p.sendMessage(ChatColor.LIGHT_PURPLE + "[포키냥] " + ChatColor.WHITE + "소환했어요. 크기 " + scale
                + ChatColor.GRAY + " (방향이 반대면 /포키냥 돌리기 180)");
    }

    private List<Entity> near(Player p, double r) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(r, r, r))
            if (e.getPersistentDataContainer().has(keyId, PersistentDataType.STRING)) out.add(e);
        return out;
    }

    private String nearestId(Player p) {
        Entity best = null;
        for (Entity e : near(p, 6))
            if (best == null || e.getLocation().distanceSquared(p.getLocation()) < best.getLocation().distanceSquared(p.getLocation())) best = e;
        return best == null ? null : best.getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    private void bob() {
        if (!getConfig().getBoolean("bob.enabled", true)) return;
        bobUp = !bobUp;
        float h = (float) getConfig().getDouble("bob.height", 0.08);
        int t = Math.max(5, getConfig().getInt("bob.ticks", 30));
        for (var w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                Float s = d.getPersistentDataContainer().get(keyScale, PersistentDataType.FLOAT);
                if (s == null) continue;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(t);
                d.setTransformation(shape(s, bobUp ? h * s : 0));
            }
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction it)) return;
        if (!it.getPersistentDataContainer().has(keyId, PersistentDataType.STRING)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("cooldown-seconds", 2) * 1000L;
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < cd) return;
        lastClick.put(p.getUniqueId(), now);
        if (messages.isEmpty()) return;
        int i = step.getOrDefault(p.getUniqueId(), 0) % messages.size();
        step.put(p.getUniqueId(), i + 1);
        for (String line : messages.get(i))
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', line.replace("{player}", p.getName())));
        String snd = getConfig().getString("sound", "");
        if (snd != null && !snd.isBlank()) {
            try { p.playSound(it.getLocation(), snd, 1f, 1.2f); } catch (Throwable ignored) { }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0] : "";
        if (sub.equals("리로드") || sub.equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "[포키냥] " + ChatColor.WHITE + "다시 불러왔어요. 안내 " + messages.size() + "묶음");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("게임 안에서만 쓸 수 있어요"); return true; }
        switch (sub) {
            case "소환", "spawn" -> {
                float s = (float) getConfig().getDouble("default-scale", 1.5);
                if (args.length > 1) try { s = Float.parseFloat(args[1]); } catch (NumberFormatException ignored) { }
                s = Math.max(0.3f, Math.min(6f, s));
                spawn(p, s);
            }
            case "제거", "remove" -> {
                String id = nearestId(p);
                if (id == null) { p.sendMessage(ChatColor.GRAY + "근처(6칸)에 포키냥이 없어요"); return true; }
                int n = 0;
                for (Entity e : near(p, 12))
                    if (id.equals(e.getPersistentDataContainer().get(keyId, PersistentDataType.STRING))) { e.remove(); n++; }
                p.sendMessage(ChatColor.LIGHT_PURPLE + "[포키냥] " + ChatColor.WHITE + "지웠어요 (" + n + ")");
            }
            case "돌리기", "rotate" -> {
                float deg = 45;
                if (args.length > 1) try { deg = Float.parseFloat(args[1]); } catch (NumberFormatException ignored) { }
                String id = nearestId(p);
                if (id == null) { p.sendMessage(ChatColor.GRAY + "근처(6칸)에 포키냥이 없어요"); return true; }
                for (Entity e : near(p, 12))
                    if (e instanceof ItemDisplay && id.equals(e.getPersistentDataContainer().get(keyId, PersistentDataType.STRING)))
                        e.setRotation(e.getLocation().getYaw() + deg, 0);
                p.sendMessage(ChatColor.LIGHT_PURPLE + "[포키냥] " + ChatColor.WHITE + deg + "도 돌렸어요");
            }
            default -> p.sendMessage(ChatColor.LIGHT_PURPLE + "/" + label + " 소환 [크기]" + ChatColor.GRAY + "  — 서 있는 자리에, 보는 방향으로 (크기 1 ≈ 2블록)\n"
                    + ChatColor.LIGHT_PURPLE + "/" + label + " 돌리기 [각도]" + ChatColor.GRAY + "  — 가까운 포키냥 회전 (기본 45)\n"
                    + ChatColor.LIGHT_PURPLE + "/" + label + " 제거" + ChatColor.GRAY + "  — 가까운 포키냥 지우기\n"
                    + ChatColor.LIGHT_PURPLE + "/" + label + " 리로드" + ChatColor.GRAY + "  — config.yml 다시 읽기");
        }
        return true;
    }
}

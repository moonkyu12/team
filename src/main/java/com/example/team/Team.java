package com.example.team;


import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public final class Team extends JavaPlugin implements CommandExecutor, Listener {

    // -------------------- 초대 구조체/맵 --------------------
    private static class PendingInvite {
        final UUID inviter, target;
        final String token;
        final int timeoutTaskId;
        PendingInvite(UUID inviter, UUID target, String token, int timeoutTaskId) {
            this.inviter = inviter; this.target = target; this.token = token; this.timeoutTaskId = timeoutTaskId;
        }
    }

    private final Map<UUID, PendingInvite> pendingByTarget  = new HashMap<>();
    private final Map<UUID, PendingInvite> pendingByInviter = new HashMap<>();
    private final Map<String, PendingInvite> pendingByToken = new HashMap<>();

    private void cancelInvite(PendingInvite p) {
        try { Bukkit.getScheduler().cancelTask(p.timeoutTaskId); } catch (Exception ignored) {}
        pendingByTarget.remove(p.target, p);
        pendingByInviter.remove(p.inviter, p);
        pendingByToken.remove(p.token, p);
    }

    private void clearInvitesFor(UUID id) {
        PendingInvite asTarget = pendingByTarget.remove(id);
        if (asTarget != null) cancelInvite(asTarget);
        PendingInvite asInviter = pendingByInviter.remove(id);
        if (asInviter != null) cancelInvite(asInviter);
    }

    // -------------------- 팀/스폰/채팅/파트너 --------------------
    private final List<Player> teamRed = new ArrayList<>();
    private final List<Player> teamBlue = new ArrayList<>();
    private Location redSpawn, blueSpawn;
    private final Set<UUID> teamChatPlayers = new HashSet<>();
    private final Map<UUID, UUID> partner = new HashMap<>();

    // -------------------- 핑 --------------------
    private NamespacedKey pingKey;
    private final Map<UUID, List<TextDisplay>> activePings = new HashMap<>();
    private final Set<UUID> pingCooldown = new HashSet<>();
    private final List<Player> teamAList = new ArrayList<>();
    private final List<Player> teamBList = new ArrayList<>();

    // -------------------- 라이프사이클 --------------------
    @Override
    public void onEnable() {
        Objects.requireNonNull(getCommand("myteam")).setExecutor(this);
        Objects.requireNonNull(getCommand("myteammsg")).setExecutor(this);
        Objects.requireNonNull(getCommand("leaveteam")).setExecutor(this);
        Objects.requireNonNull(getCommand("teamspawn")).setExecutor(this);
        Objects.requireNonNull(getCommand("start")).setExecutor(this);
        Objects.requireNonNull(getCommand("tc")).setExecutor(this);
        Objects.requireNonNull(getCommand("ping")).setExecutor(this);
        Objects.requireNonNull(getCommand("quickmatch")).setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        pingKey = new NamespacedKey(this, "ping");

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        if (board.getTeam("team_red") == null) board.registerNewTeam("team_red");
        if (board.getTeam("team_blue") == null) board.registerNewTeam("team_blue");
    }

    @Override
    public void onDisable() {
        for (PendingInvite p : pendingByToken.values()) {
            Bukkit.getScheduler().cancelTask(p.timeoutTaskId);
        }
        pendingByTarget.clear();
        pendingByInviter.clear();
        pendingByToken.clear();
        partner.clear();
    }

    // -------------------- 명령 처리 --------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("플레이어만 사용 가능합니다."); return true; }
        Player player = (Player) sender;

        switch (command.getName().toLowerCase(Locale.ROOT)) {

            // ---------- /myteam ----------
            case "myteam": {
                if (args.length == 1) {
                    String targetName = args[0];
                    Player target = Bukkit.getPlayerExact(targetName);
                    if (target == null || !target.isOnline()) { player.sendMessage(ChatColor.RED + "해당 플레이어가 온라인이 아닙니다."); return true; }
                    if (target.getUniqueId().equals(player.getUniqueId())) { player.sendMessage(ChatColor.RED + "자기 자신에게는 보낼 수 없습니다."); return true; }
                    if (pendingByTarget.containsKey(target.getUniqueId())) { player.sendMessage(ChatColor.YELLOW + "그 플레이어에게 이미 대기중인 초대가 있습니다."); return true; }
                    if (pendingByInviter.containsKey(player.getUniqueId())) {
                        player.sendMessage(ChatColor.YELLOW + "이미 다른 초대가 대기 중입니다. 수락/거절 또는 만료를 기다려주세요.");
                        return true;
                    }

                    String token = UUID.randomUUID().toString().substring(0, 8);
                    int taskId = Bukkit.getScheduler().runTaskLater(this, () -> {
                        PendingInvite p = pendingByToken.remove(token);
                        if (p != null) {
                            pendingByInviter.remove(p.inviter);
                            Player inv = Bukkit.getPlayer(p.inviter);
                            Player tgt = Bukkit.getPlayer(p.target);
                            if (inv != null) inv.sendMessage(ChatColor.GRAY + "[팀] " + (tgt != null ? tgt.getName() : "상대") + " 초대가 만료되었습니다.");
                            if (tgt != null) tgt.sendMessage(ChatColor.GRAY + "[팀] 초대가 만료되었습니다.");
                        }
                    }, 20L * 30).getTaskId();

                    PendingInvite pending = new PendingInvite(player.getUniqueId(), target.getUniqueId(), token, taskId);
                    pendingByTarget.put(target.getUniqueId(), pending);
                    pendingByInviter.put(player.getUniqueId(), pending);
                    pendingByToken.put(token, pending);

                    Component acceptBtn = Component.text("[수락]")
                            .color(NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
                            .hoverEvent(HoverEvent.showText(Component.text("팀 초대를 수락합니다")))
                            .clickEvent(ClickEvent.runCommand("/myteam accept " + token));
                    Component denyBtn = Component.text("[거절]")
                            .color(NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                            .hoverEvent(HoverEvent.showText(Component.text("팀 초대를 거절합니다")))
                            .clickEvent(ClickEvent.runCommand("/myteam deny " + token));

                    Component msg = Component.text(player.getName() + " 님이 같이 팀하실래요? ")
                            .color(NamedTextColor.AQUA)
                            .append(acceptBtn).append(Component.space()).append(denyBtn);

                    target.sendMessage(msg);
                    player.sendMessage(ChatColor.YELLOW + "[팀] " + target.getName() + "에게 초대를 보냈습니다. (30초 유효)");
                    return true;
                }

                if (args.length == 2) {
                    String sub = args[0].toLowerCase(Locale.ROOT);
                    String token = args[1];

                    PendingInvite p = pendingByToken.get(token);
                    if (p == null) { player.sendMessage(ChatColor.RED + "유효하지 않거나 만료된 초대입니다."); return true; }
                    if (!p.target.equals(player.getUniqueId())) { player.sendMessage(ChatColor.RED + "이 초대는 당신에게 온 것이 아닙니다."); return true; }

                    Player inviterPlayer = Bukkit.getPlayer(p.inviter);
                    Player targetPlayer  = Bukkit.getPlayer(p.target);

                    Runnable cleanup = () -> {
                        Bukkit.getScheduler().cancelTask(p.timeoutTaskId);
                        pendingByToken.remove(p.token);
                        pendingByTarget.remove(p.target);
                        pendingByInviter.remove(p.inviter);
                    };

                    if (sub.equals("accept")) {
                        cleanup.run();
                        partner.put(p.inviter, p.target);
                        partner.put(p.target, p.inviter);
                        if (inviterPlayer != null) inviterPlayer.sendMessage(ChatColor.GREEN + "[팀] 초대가 수락되어 서로 팀으로 묶였습니다!");
                        if (targetPlayer  != null) targetPlayer.sendMessage(ChatColor.GREEN + "[팀] 초대를 수락했습니다. 팀으로 묶였습니다!");
                        return true;
                    } else if (sub.equals("deny")) {
                        cleanup.run();
                        if (inviterPlayer != null) inviterPlayer.sendMessage(ChatColor.YELLOW + "[팀] 상대가 초대를 거절했습니다.");
                        if (targetPlayer  != null) targetPlayer.sendMessage(ChatColor.GRAY + "[팀] 초대를 거절했습니다.");
                        return true;
                    } else {
                        player.sendMessage(ChatColor.YELLOW + "사용법: /myteam <플레이어>  또는  /myteam <accept|deny> <토큰>");
                        return true;
                    }
                }

                player.sendMessage(ChatColor.YELLOW + "사용법: /myteam <플레이어>");
                return true;
            }

            case "leaveteam": {
                UUID uuid = player.getUniqueId();

                if (partner.containsKey(uuid)) {
                    UUID partnerUuid = partner.remove(uuid);
                    partner.remove(partnerUuid);
                    Player partnerPlayer = Bukkit.getPlayer(partnerUuid);
                    if (partnerPlayer != null && partnerPlayer.isOnline()) {
                        partnerPlayer.sendMessage(ChatColor.YELLOW + "[팀] " + player.getName() + "님이 팀에서 나갔습니다.");
                    }
                    player.sendMessage(ChatColor.YELLOW + "[팀] 팀에서 나왔습니다.");
                }

                if (teamRed.remove(player)) {
                    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                    org.bukkit.scoreboard.Team red = board.getTeam("team_red");
                    if (red != null) red.removeEntry(player.getName());
                    player.sendMessage(ChatColor.YELLOW + "[팀] Team RED에서 나왔습니다.");
                }

                if (teamBlue.remove(player)) {
                    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                    org.bukkit.scoreboard.Team blue = board.getTeam("team_blue");
                    if (blue != null) blue.removeEntry(player.getName());
                    player.sendMessage(ChatColor.YELLOW + "[팀] Team BLUE에서 나왔습니다.");
                }

                teamChatPlayers.remove(uuid);
                return true;
            }

            case "myteammsg": {
                if (args.length == 0) {
                    player.sendMessage(ChatColor.YELLOW + "사용법: /myteammsg <메시지>");
                    return true;
                }

                UUID partnerUuid = partner.get(player.getUniqueId());
                if (partnerUuid == null) {
                    player.sendMessage(ChatColor.RED + "[팀] 현재 짝이 없습니다. /myteam 으로 먼저 짝을 맺어주세요.");
                    return true;
                }

                Player mate = Bukkit.getPlayer(partnerUuid);
                String msg = String.join(" ", args);

                player.sendMessage(ChatColor.DARK_AQUA + "[짝] " + ChatColor.WHITE + player.getName()
                        + ChatColor.GRAY + " → " + ChatColor.WHITE + (mate != null ? mate.getName() : "오프라인")
                        + ChatColor.GRAY + ": " + ChatColor.AQUA + msg);

                if (mate != null && mate.isOnline()) {
                    mate.sendMessage(ChatColor.DARK_AQUA + "[짝] " + ChatColor.WHITE + player.getName()
                            + ChatColor.GRAY + ": " + ChatColor.AQUA + msg);
                    mate.playSound(mate.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                } else {
                    player.sendMessage(ChatColor.GRAY + "[팀] 상대가 오프라인입니다.");
                }
                return true;
            }

            // ---------- /teamspawn ----------
            case "teamspawn": {
                if (args.length != 1) { player.sendMessage(ChatColor.YELLOW + "사용법: /teamspawn <red|blue>"); return true; }
                if (args[0].equalsIgnoreCase("red")) {
                    redSpawn = player.getLocation();
                    player.sendMessage(ChatColor.RED + "Team RED 스폰이 설정되었습니다.");
                } else if (args[0].equalsIgnoreCase("blue")) {
                    blueSpawn = player.getLocation();
                    player.sendMessage(ChatColor.BLUE + "Team BLUE 스폰이 설정되었습니다.");
                } else {
                    player.sendMessage(ChatColor.YELLOW + "사용법: /teamspawn <red|blue>");
                }
                return true;
            }

            // ---------- /start ----------
            case "start": {
                if (redSpawn == null || blueSpawn == null) { player.sendMessage(ChatColor.RED + "스폰 위치가 모두 설정되지 않았습니다."); return true; }
                for (Player p : teamRed) p.teleport(redSpawn);
                for (Player p : teamBlue) p.teleport(blueSpawn);
                Bukkit.broadcastMessage(ChatColor.GOLD + "게임이 시작되었습니다!");
                return true;
            }

            // ---------- /quickmatch ----------
            case "quickmatch": {
                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                if (players.size() != 4) { player.sendMessage(ChatColor.RED + "정확히 4명이 접속 중이어야 퀵매치를 시작할 수 있습니다."); return true; }

                Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                org.bukkit.scoreboard.Team red = board.getTeam("team_red");
                org.bukkit.scoreboard.Team blue = board.getTeam("team_blue");
                red.getEntries().forEach(red::removeEntry);
                blue.getEntries().forEach(blue::removeEntry);

                Collections.shuffle(players);
                teamRed.clear(); teamBlue.clear();

                teamRed.add(players.get(0)); teamRed.add(players.get(1));
                teamBlue.add(players.get(2)); teamBlue.add(players.get(3));

                for (Player p : teamRed) { red.addEntry(p.getName()); p.sendMessage(ChatColor.RED + "[퀵매치] 당신은 Team RED입니다!"); }
                for (Player p : teamBlue){ blue.addEntry(p.getName()); p.sendMessage(ChatColor.BLUE + "[퀵매치] 당신은 Team BLUE입니다!"); }

                if (redSpawn == null || blueSpawn == null) { Bukkit.broadcastMessage(ChatColor.RED + "[퀵매치] 스폰 위치가 설정되지 않아 시작할 수 없습니다."); return true; }
                for (Player p : teamRed) p.teleport(redSpawn);
                for (Player p : teamBlue) p.teleport(blueSpawn);
                Bukkit.broadcastMessage(ChatColor.GOLD + "[퀵매치] 게임이 시작되었습니다!");
                return true;
            }

            // ---------- /tc ----------
            case "tc": {
                UUID uuid = player.getUniqueId();
                if (teamChatPlayers.contains(uuid)) { teamChatPlayers.remove(uuid); player.sendMessage(ChatColor.YELLOW + "[팀 채팅] 비활성화"); }
                else { teamChatPlayers.add(uuid); player.sendMessage(ChatColor.GREEN + "[팀 채팅] 활성화"); }
                return true;
            }

            // ---------- /ping ----------
            case "ping": {
                ItemStack paper = new ItemStack(Material.PAPER);
                ItemMeta itemMeta = paper.getItemMeta();
                itemMeta.setDisplayName(ChatColor.BLUE + "§l핑 소환지");
                itemMeta.setCustomModelData(123456);
                itemMeta.getPersistentDataContainer().set(pingKey, PersistentDataType.BYTE, (byte)1);
                itemMeta.setUnbreakable(true);
                paper.setItemMeta(itemMeta);
                player.getInventory().addItem(paper);
                player.sendMessage(ChatColor.AQUA + "핑 종이를 받았습니다!");
                return true;
            }

            default:
                return false;
        }
    }

    // -------------------- 유틸 --------------------
    private boolean isPartnered(Player a, Player b) {
        UUID pa = partner.get(a.getUniqueId());
        return pa != null && pa.equals(b.getUniqueId());
    }

    // -------------------- 이벤트: 핑 사용 --------------------
    @EventHandler
    public void onPingUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (pingCooldown.contains(player.getUniqueId())) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.PAPER) return;
        if (!item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(pingKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);

        UUID uuid = player.getUniqueId();
        pingCooldown.add(uuid);
        Bukkit.getScheduler().runTaskLater(this, () -> pingCooldown.remove(uuid), 1L);

        activePings.putIfAbsent(uuid, new ArrayList<>());
        List<TextDisplay> playerPings = activePings.get(uuid);

        if (playerPings.size() >= 5) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            player.sendMessage(ChatColor.RED + "핑은 최대 5개까지만 생성할 수 있습니다.");
            return;
        }

        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection().normalize();

        RayTraceResult result = player.getWorld().rayTraceBlocks(eyeLoc, direction, 50);
        Location targetLoc = (result != null && result.getHitPosition() != null)
                ? result.getHitPosition().toLocation(player.getWorld()).add(direction.multiply(-0.3))
                : eyeLoc.clone().add(direction.multiply(50));

        TextDisplay text = player.getWorld().spawn(targetLoc, TextDisplay.class, td -> {
            td.setText("§b§l♦");
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(false);
            td.setSeeThrough(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new Quaternionf(),
                    new Vector3f(2.5f, 2.5f, 2.5f),
                    new Quaternionf()
            ));
        });

        playerPings.add(text);
        activePings.put(uuid, playerPings);

        List<Player> teammates = teamAList.contains(player) ? teamAList : (teamBList.contains(player) ? teamBList : List.of(player));
        for (Player p : teammates) {
            if (!p.isOnline()) continue;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                List<TextDisplay> updatedList = activePings.get(uuid);
                if (text.isValid()) text.remove();
                if (updatedList != null) {
                    updatedList.remove(text);
                    if (updatedList.isEmpty()) {
                        activePings.remove(uuid);
                    }
                }
            }
        }.runTaskLater(this, 20 * 12);
    }

    // -------------------- 이벤트: 팀/전체 채팅 --------------------
    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        UUID uuid = sender.getUniqueId();

        // 1) @팀 강제 팀채팅
        if (raw.startsWith("@팀")) {
            event.setCancelled(true);
            String body = raw.substring(2).trim();
            String prefix = "§7[팀] " + sender.getName() + "§f: ";
            if (teamRed.contains(sender)) {
                for (Player p : teamRed) p.sendMessage(prefix + body);
            } else if (teamBlue.contains(sender)) {
                for (Player p : teamBlue) p.sendMessage(prefix + body);
            } else {
                sender.sendMessage(ChatColor.RED + "팀에 속해있지 않습니다.");
            }
            return;
        }

        // 2) 토글 팀채팅 모드 (단, @전체로 시작하면 전체로 보냄)
        if (teamChatPlayers.contains(uuid) && !raw.startsWith("@전체")) {
            event.setCancelled(true);
            String prefix = "§7[팀] " + sender.getName() + "§f: ";
            if (teamRed.contains(sender)) {
                for (Player p : teamRed) p.sendMessage(prefix + raw);
            } else if (teamBlue.contains(sender)) {
                for (Player p : teamBlue) p.sendMessage(prefix + raw);
            } else {
                sender.sendMessage(ChatColor.RED + "팀에 속해있지 않습니다.");
            }
            return;
        }

        // 3) @전체 → 토큰 제거 후 전체로
        if (raw.startsWith("@전체")) {
            raw = raw.substring(3).trim();
            event.message(Component.text(raw));
        }

        // 4) 기본(전체) 채팅 렌더러
        final String finalRaw = raw;
        event.renderer((source, displayName, message, viewer) ->
                Component.text("[전체] ").color(NamedTextColor.YELLOW)
                        .append(displayName.color(NamedTextColor.WHITE))
                        .append(Component.text(": "))
                        .append(Component.text(finalRaw))
        );
    }

    // -------------------- 이벤트: 로그아웃 --------------------
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID quitting = player.getUniqueId();
        String name = player.getName();

        // 내가 '보낸' 초대가 있으면 취소
        PendingInvite sent = pendingByInviter.remove(quitting);
        if (sent != null) {
            Bukkit.getScheduler().cancelTask(sent.timeoutTaskId);
            pendingByToken.remove(sent.token, sent);
            pendingByTarget.remove(sent.target, sent);

            Player tgt = Bukkit.getPlayer(sent.target);
            if (tgt != null) {
                tgt.sendMessage(ChatColor.YELLOW + "[팀] " + name + "님이 로그아웃하여 초대가 취소되었습니다.");
            }
        }

        // 내가 '받은' 초대가 있으면 취소
        PendingInvite received = pendingByTarget.remove(quitting);
        if (received != null) {
            Bukkit.getScheduler().cancelTask(received.timeoutTaskId);
            pendingByToken.remove(received.token, received);
            pendingByInviter.remove(received.inviter, received);

            Player inv = Bukkit.getPlayer(received.inviter);
            if (inv != null) {
                inv.sendMessage(ChatColor.YELLOW + "[팀] " + name + "님이 로그아웃하여 초대가 취소되었습니다.");
            }
        }

        // 혹시 모를 잔여(보낸/받은) 전부 정리
        clearInvitesFor(quitting);
    }
}

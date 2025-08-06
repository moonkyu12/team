package com.example.team;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.Color;


import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.*;

public final class Team extends JavaPlugin implements CommandExecutor, Listener {

    // 팀 구성 저장용 리스트
    private final List<Player> teamRed = new ArrayList<>();
    private final List<Player> teamBlue = new ArrayList<>();

    // 팀 스폰 지점
    private Location redSpawn;
    private Location blueSpawn;

    // 팀 채팅 활성화 유저
    private final Set<UUID> teamChatPlayers = new HashSet<>();

    // 플레이어별 핑 저장
    private final Map<UUID, List<TextDisplay>> activePings = new HashMap<>();

    private NamespacedKey pingKey;

    @Override
    public void onEnable() {
        // 명령어 등록
        getCommand("myteam").setExecutor(this);
        getCommand("teamspawn").setExecutor(this);
        getCommand("start").setExecutor(this);
        getCommand("tc").setExecutor(this);
        getCommand("ping").setExecutor(this);
        getCommand("quickmatch").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        pingKey = new NamespacedKey(this, "ping");

        // Scoreboard에 팀 미리 등록
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        if (board.getTeam("team_red") == null) board.registerNewTeam("team_red");
        if (board.getTeam("team_blue") == null) board.registerNewTeam("team_blue");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        switch (label.toLowerCase()) {
            case "team": {
                // 접속 인원 확인 및 팀 나누기
                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                if (players.size() != 4) {
                    player.sendMessage(ChatColor.RED + "정확히 4명이 접속 중이어야 합니다.");
                    return true;
                }

                // 팀 클리어 및 재설정
                Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                org.bukkit.scoreboard.Team red = board.getTeam("team_red");
                org.bukkit.scoreboard.Team blue = board.getTeam("team_blue");
                red.getEntries().forEach(red::removeEntry);
                blue.getEntries().forEach(blue::removeEntry);

                Collections.shuffle(players);
                teamRed.clear();
                teamBlue.clear();

                // 랜덤 분배
                teamRed.add(players.get(0));
                teamRed.add(players.get(1));
                teamBlue.add(players.get(2));
                teamBlue.add(players.get(3));

                for (Player p : teamRed) {
                    red.addEntry(p.getName());
                    p.sendMessage(ChatColor.RED + "[팀 시스템] 당신은 Team RED입니다!");
                }
                for (Player p : teamBlue) {
                    blue.addEntry(p.getName());
                    p.sendMessage(ChatColor.BLUE + "[팀 시스템] 당신은 Team BLUE입니다!");
                }

                Bukkit.broadcastMessage(ChatColor.YELLOW + "팀이 성공적으로 나뉘었습니다.");
                return true;
            }

            case "teamspawn": {
                // 팀별 스폰 위치 설정
                if (args.length != 1) {
                    player.sendMessage(ChatColor.YELLOW + "사용법: /teamspawn <red|blue>");
                    return true;
                }
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

            case "start": {
                // 게임 시작: 팀원들을 각 스폰지점으로 이동
                if (redSpawn == null || blueSpawn == null) {
                    player.sendMessage(ChatColor.RED + "스폰 위치가 모두 설정되지 않았습니다.");
                    return true;
                }
                for (Player p : teamRed) p.teleport(redSpawn);
                for (Player p : teamBlue) p.teleport(blueSpawn);
                Bukkit.broadcastMessage(ChatColor.GOLD + "게임이 시작되었습니다!");
                return true;
            }

            case "quickmatch": {
                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                if (players.size() != 4) {
                    player.sendMessage(ChatColor.RED + "정확히 4명이 접속 중이어야 퀵매치를 시작할 수 있습니다.");
                    return true;
                }

                Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                org.bukkit.scoreboard.Team red = board.getTeam("team_red");
                org.bukkit.scoreboard.Team blue = board.getTeam("team_blue");

                red.getEntries().forEach(red::removeEntry);
                blue.getEntries().forEach(blue::removeEntry);

                Collections.shuffle(players);
                teamRed.clear();
                teamBlue.clear();

                teamRed.add(players.get(0));
                teamRed.add(players.get(1));
                teamBlue.add(players.get(2));
                teamBlue.add(players.get(3));

                for (Player p : teamRed) {
                    red.addEntry(p.getName());
                    p.sendMessage(ChatColor.RED + "[퀵매치] 당신은 Team RED입니다!");
                }
                for (Player p : teamBlue) {
                    blue.addEntry(p.getName());
                    p.sendMessage(ChatColor.BLUE + "[퀵매치] 당신은 Team BLUE입니다!");
                }

                // 자동 시작
                if (redSpawn == null || blueSpawn == null) {
                    Bukkit.broadcastMessage(ChatColor.RED + "[퀵매치] 스폰 위치가 설정되지 않아 시작할 수 없습니다.");
                    return true;
                }

                for (Player p : teamRed) p.teleport(redSpawn);
                for (Player p : teamBlue) p.teleport(blueSpawn);

                Bukkit.broadcastMessage(ChatColor.GOLD + "[퀵매치] 게임이 시작되었습니다!");
                return true;
            }


            case "tc": {
                // 팀 채팅 모드 on/off
                UUID uuid = player.getUniqueId();
                if (teamChatPlayers.contains(uuid)) {
                    teamChatPlayers.remove(uuid);
                    player.sendMessage(ChatColor.YELLOW + "[팀 채팅] 팀 채팅 모드가 비활성화되었습니다.");
                } else {
                    teamChatPlayers.add(uuid);
                    player.sendMessage(ChatColor.GREEN + "[팀 채팅] 팀 채팅 모드가 활성화되었습니다.");
                }
                return true;
            }

            case "ping": {
                // 핑 종이 지급 명령어
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
        }
        return false;
    }

    @EventHandler
    public void onPingUse(PlayerInteractEvent event) {
        // 핑 종이 사용 시 처리
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() != Material.PAPER) return;
        if (!item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(pingKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);

        UUID uuid = player.getUniqueId();
        activePings.putIfAbsent(uuid, new ArrayList<>());
        List<TextDisplay> playerPings = activePings.get(uuid);

        if (playerPings.size() >= 3) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            player.sendMessage(ChatColor.RED + "핑은 최대 3개까지만 생성할 수 있습니다.");
            return;
        }

        // 핑 위치 계산
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location pingLoc = null;
        for (int i = 0; i <= 50; i++) {
            Location check = eye.clone().add(dir.clone().multiply(i));
            if (check.getBlock().getType().isSolid()) {
                pingLoc = check.getBlock().getLocation().add(0.5, 1.2, 0.5);
                break;
            }
        }
        if (pingLoc == null) pingLoc = eye.clone().add(dir.multiply(50));

        // TextDisplay 생성
        TextDisplay display = (TextDisplay) player.getWorld().spawnEntity(pingLoc, EntityType.TEXT_DISPLAY);
        display.setCustomName(ChatColor.AQUA + "♦");
        display.setCustomNameVisible(true);

        // ✅ 십자가 가운데에 정확히 위치
        pingLoc.add(0.5, 1.2, 0.5); // X, Y, Z 중심 보정

        // ✅ 배경 제거
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0)); // 완전 투명

        // ✅ 나머지 설정
        display.setBillboard(Display.Billboard.VERTICAL);
        display.setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(),
                new Vector3f(2.5f, 2.5f, 2.5f), new Quaternionf()
        ));


        playerPings.add(display);

        // 12초 후 자동 제거
        Bukkit.getScheduler().runTaskLater(this, () -> {
            display.remove();
            playerPings.remove(display);
        }, 20L * 12);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        // 채팅 핸들링 (팀 채팅 / 전체 채팅)
        Player sender = event.getPlayer();
        String msg = event.getMessage();
        UUID uuid = sender.getUniqueId();

        if (msg.startsWith("@팀")) {
            event.setCancelled(true);
            String teamMsg = msg.replaceFirst("@팀", "").trim();
            String formatted = ChatColor.GRAY + "[팀] " + sender.getName() + ": " + ChatColor.WHITE + teamMsg;

            if (teamRed.contains(sender)) {
                for (Player p : teamRed) p.sendMessage(formatted);
            } else if (teamBlue.contains(sender)) {
                for (Player p : teamBlue) p.sendMessage(formatted);
            } else {
                sender.sendMessage(ChatColor.RED + "팀에 속해있지 않습니다.");
            }

        } else if (msg.startsWith("@전체")) {
            event.setMessage(msg.replaceFirst("@전체", "").trim());

        } else if (teamChatPlayers.contains(uuid)) {
            event.setCancelled(true);
            String formatted = ChatColor.GRAY + "[팀] " + sender.getName() + ": " + ChatColor.WHITE + msg;

            if (teamRed.contains(sender)) {
                for (Player p : teamRed) p.sendMessage(formatted);
            } else if (teamBlue.contains(sender)) {
                for (Player p : teamBlue) p.sendMessage(formatted);
            }

        } else {
            event.setFormat(ChatColor.YELLOW + "[전체] " + sender.getName() + ": " + ChatColor.WHITE + msg);
        }
    }
}

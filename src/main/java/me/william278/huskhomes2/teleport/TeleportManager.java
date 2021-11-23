package me.william278.huskhomes2.teleport;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.papermc.lib.PaperLib;
import me.william278.huskhomes2.HuskHomes;
import me.william278.huskhomes2.MessageManager;
import me.william278.huskhomes2.api.events.PlayerPreTeleportEvent;
import me.william278.huskhomes2.data.DataManager;
import me.william278.huskhomes2.data.message.CrossServerMessageHandler;
import me.william278.huskhomes2.data.message.Message;
import me.william278.huskhomes2.integrations.VaultIntegration;
import me.william278.huskhomes2.teleport.points.RandomPoint;
import me.william278.huskhomes2.teleport.points.TeleportationPoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.logging.Level;

public class TeleportManager {

    private static final HuskHomes plugin = HuskHomes.getInstance();

    private static TeleportationPoint spawnLocation;

    // Fires the PreTeleportEvent; returns true if the event is cancelled
    private static boolean isEventCancelled(Player player, TeleportationPoint targetPoint) {
        PlayerPreTeleportEvent preTeleportEvent = new PlayerPreTeleportEvent(player, targetPoint);
        Bukkit.getPluginManager().callEvent(preTeleportEvent);
        return preTeleportEvent.isCancelled();
    }

    // Teleport a player to a player by name
    public static void teleportPlayer(Player player, String targetPlayer) {
        final TeleportationPoint destination = new TeleportationPoint(player.getLocation(),
                HuskHomes.getSettings().getServerID());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isEventCancelled(player, destination)) return;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (Connection connection = HuskHomes.getConnection()) {
                    DataManager.setPlayerLastPosition(player, destination, connection);
                    setPlayerDestinationFromTargetPlayer(player, targetPlayer, connection);
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "An SQL exception occurred teleporting a player.", e);
                }
            });
        });
    }

    // Teleport a player to a location on the server or proxy server network
    public static void teleportPlayer(Player player, TeleportationPoint point) {
        final TeleportationPoint destination = new TeleportationPoint(player.getLocation(),
                HuskHomes.getSettings().getServerID());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isEventCancelled(player, destination)) return;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (Connection connection = HuskHomes.getConnection()) {
                    DataManager.setPlayerLastPosition(player, destination, connection);
                    DataManager.setPlayerDestinationLocation(player, point, connection);
                    teleportPlayer(player);
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "An SQL exception occurred teleporting a player.", e);
                }
            });
        });
    }

    // Move a player to a different server in the bungee network
    @SuppressWarnings("UnstableApiUsage")
    private static void movePlayerServer(Player p, String targetServer) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(targetServer);
            p.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        });
    }

    public static void teleportPlayer(Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = HuskHomes.getConnection()) {
                TeleportationPoint teleportationPoint = DataManager.getPlayerDestination(p, connection);
                if (teleportationPoint != null) {
                    String server = teleportationPoint.getServer();
                    if (!HuskHomes.getSettings().doBungee() || server.equals(HuskHomes.getSettings().getServerID())) {
                        DataManager.setPlayerTeleporting(p, false, connection);
                        DataManager.deletePlayerDestination(p.getName(), connection);
                        Location targetLocation = teleportationPoint.getLocation();
                        Bukkit.getScheduler().runTask(plugin, () -> PaperLib.teleportAsync(p, targetLocation).thenRun(() -> {
                            p.playSound(targetLocation, HuskHomes.getSettings().getTeleportationCompleteSound(), 1, 1);
                            MessageManager.sendMessage(p, "teleporting_complete");
                        }));
                    } else if (HuskHomes.getSettings().doBungee()) {
                        DataManager.setPlayerDestinationLocation(p, teleportationPoint, connection);
                        DataManager.setPlayerTeleporting(p, true, connection);
                        movePlayerServer(p, server);
                    }
                }
            } catch (SQLException sqlException) {
                plugin.getLogger().log(Level.SEVERE, "An SQL exception occurred!", sqlException);
            } catch (IllegalArgumentException illegalArgumentException) {
                MessageManager.sendMessage(p, "error_invalid_on_arrival");
            }
        });
    }

    public static void queueTimedTeleport(Player player, String targetPlayer) {
        if (player.hasPermission("huskhomes.bypass_timer")) {
            teleportPlayer(player, targetPlayer);
            return;
        }

        new TimedTeleport(player, targetPlayer).begin();
    }

    public static void queueTimedTeleport(Player player, TeleportationPoint point) {
        if (player.hasPermission("huskhomes.bypass_timer")) {
            teleportPlayer(player, point);
            return;
        }

        new TimedTeleport(player, point, TimedTeleport.TargetType.POINT).begin();
    }

    public static void teleportToOfflinePlayer(Player player, String targetPlayer, Connection connection) throws SQLException {
        final Integer playerID = DataManager.getPlayerId(targetPlayer, connection);
        if (playerID == null) {
            MessageManager.sendMessage(player, "error_player_not_found", targetPlayer);
            return;
        }
        TeleportationPoint offlinePoint = DataManager.getPlayerOfflinePosition(playerID, connection);
        if (offlinePoint == null) {
            MessageManager.sendMessage(player, "error_no_offline_position", targetPlayer);
            return;
        }
        MessageManager.sendMessage(player, "teleporting_offline_player", targetPlayer);
        teleportPlayer(player, offlinePoint);
    }

    public static void queueBackTeleport(Player player, Connection connection) throws SQLException {
        try {
            TeleportationPoint lastPosition = DataManager.getPlayerLastPosition(player, connection);
            if (lastPosition != null) {
                if (HuskHomes.getSettings().doEconomy()) {
                    double backCost = HuskHomes.getSettings().getBackCost();
                    if (backCost > 0) {
                        if (!VaultIntegration.hasMoney(player, backCost)) {
                            MessageManager.sendMessage(player, "error_insufficient_funds", VaultIntegration.format(backCost));
                            return;
                        }
                    }
                }
                if (player.hasPermission("huskhomes.bypass_timer")) {
                    if (HuskHomes.getSettings().doEconomy()) {
                        double backCost = HuskHomes.getSettings().getRtpCost();
                        if (backCost > 0) {
                            VaultIntegration.takeMoney(player, backCost);
                            MessageManager.sendMessage(player, "back_spent_money", VaultIntegration.format(backCost));
                        }
                    }
                    teleportPlayer(player, lastPosition);
                    return;
                }

                new TimedTeleport(player, lastPosition, TimedTeleport.TargetType.BACK).begin();
            } else {
                MessageManager.sendMessage(player, "error_no_last_position");
            }
        } catch (IllegalArgumentException e) {
            MessageManager.sendMessage(player, "error_no_last_position");
        }

    }

    public static void queueRandomTeleport(Player player, Connection connection) throws SQLException {
        if (!player.hasPermission("huskhomes.rtp.bypass_cooldown")) {
            long currentTime = Instant.now().getEpochSecond();
            long coolDownTime = DataManager.getPlayerRtpCoolDown(player, connection);
            if (currentTime < coolDownTime) {
                long timeRemaining = coolDownTime - currentTime;
                long timeRemainingMinutes = timeRemaining / 60;
                MessageManager.sendMessage(player, "error_rtp_cooldown", Long.toString(timeRemainingMinutes));
                return;
            }
        }
        if (HuskHomes.getSettings().doEconomy()) {
            double rtpCost = HuskHomes.getSettings().getRtpCost();
            if (rtpCost > 0) {
                if (!VaultIntegration.hasMoney(player, rtpCost)) {
                    MessageManager.sendMessage(player, "error_insufficient_funds", VaultIntegration.format(rtpCost));
                    return;
                }
            }
        }
        if (player.hasPermission("huskhomes.bypass_timer")) {
            RandomPoint randomPoint = new RandomPoint(player);
            if (randomPoint.hasFailed()) {
                return;
            }
            if (HuskHomes.getSettings().doEconomy()) {
                double rtpCost = HuskHomes.getSettings().getRtpCost();
                if (rtpCost > 0) {
                    VaultIntegration.takeMoney(player, rtpCost);
                    MessageManager.sendMessage(player, "rtp_spent_money", VaultIntegration.format(rtpCost));
                }
            }
            teleportPlayer(player, randomPoint);
            DataManager.updateRtpCoolDown(player, connection);
            return;
        }
        new TimedTeleport(player).begin();
    }

    public static void teleportHere(Player requester, String targetPlayerName) throws SQLException {
        Player targetPlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (targetPlayer != null) {
            if (targetPlayer.getUniqueId() != requester.getUniqueId()) {
                teleportPlayer(targetPlayer, requester.getName());
            } else {
                MessageManager.sendMessage(requester, "error_tp_self");
            }
        } else {
            if (HuskHomes.getSettings().doBungee()) {
                teleportHereCrossServer(requester, targetPlayerName);
                return;
            }
            MessageManager.sendMessage(requester, "error_player_not_found", targetPlayerName);
        }
    }

    private static void teleportHereCrossServer(Player requester, String targetPlayerName) {
        CrossServerMessageHandler.getMessage(targetPlayerName, Message.MessageType.TELEPORT_TO_ME, requester.getName()).send(requester);
    }

    private static void setTeleportationDestinationCrossServer(Player requester, String targetPlayerName) {
        CrossServerMessageHandler.getMessage(targetPlayerName, Message.MessageType.SET_TP_DESTINATION, requester.getName()).send(requester);
    }

    public static void setPlayerDestinationFromTargetPlayer(Player requester, String targetPlayerName, Connection connection) throws SQLException {
        Player targetPlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (targetPlayer != null) {
            if (requester.getUniqueId() != targetPlayer.getUniqueId()) {
                DataManager.setPlayerDestinationLocation(requester,
                        new TeleportationPoint(targetPlayer.getLocation(), HuskHomes.getSettings().getServerID()), connection);
                teleportPlayer(requester);
            } else {
                MessageManager.sendMessage(requester, "error_tp_self");
            }
        } else {
            if (HuskHomes.getSettings().doBungee()) {
                setTeleportationDestinationCrossServer(requester, targetPlayerName);
                return;
            }
            MessageManager.sendMessage(requester, "error_player_not_found", targetPlayerName);
        }
    }

    public static TeleportationPoint getSpawnLocation() {
        return spawnLocation;
    }

    public static void setSpawnLocation(TeleportationPoint spawnLocation) {
        TeleportManager.spawnLocation = spawnLocation;
    }
}

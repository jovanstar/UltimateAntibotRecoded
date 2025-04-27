package me.kr1s_d.ultimateantibot.listener;

import me.kr1s_d.ultimateantibot.Notificator;
import me.kr1s_d.ultimateantibot.common.AttackState;
import me.kr1s_d.ultimateantibot.common.IAntiBotPlugin;
import me.kr1s_d.ultimateantibot.common.ModeType;
import me.kr1s_d.ultimateantibot.common.core.detectors.FastJoinBypassDetector;
import me.kr1s_d.ultimateantibot.common.service.AttackTrackerService;
import me.kr1s_d.ultimateantibot.common.utils.ConfigManger;
import me.kr1s_d.ultimateantibot.common.utils.ServerUtil;
import me.kr1s_d.ultimateantibot.event.AttackStateEvent;
import me.kr1s_d.ultimateantibot.event.DuringAttackIPJoinEvent;
import me.kr1s_d.ultimateantibot.event.ModeEnableEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;

public class CustomEventListener implements Listener {
    private final IAntiBotPlugin plugin;
    private final Plugin bukkitPlugin;
    private final FastJoinBypassDetector bypassDetector;
    private final AttackTrackerService trackerService;

    public CustomEventListener(IAntiBotPlugin plugin) {
        this.plugin = plugin;
        this.bukkitPlugin = plugin.getPlugin(); // Make sure IAntiBotPlugin exposes getPlugin()
        this.bypassDetector = new FastJoinBypassDetector(plugin);
        this.trackerService = plugin.getAttackTrackerService();
    }

    @EventHandler
    public void onAttack(ModeEnableEvent e){
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("uab.notification.automatic")) {
                Notificator.automaticNotification(player);
            }
        }

        if (e.getEnabledMode().equals(ModeType.ANTIBOT) || e.getEnabledMode().equals(ModeType.SLOW)) {
            if (ConfigManger.antibotDisconnect) {
                e.disconnectBots();
            }
        }
    }

    @EventHandler
    public void onAttackStop(AttackStateEvent e){
        if (e.getAttackState() != AttackState.STOPPED) {
            return;
        }

        // Folia GlobalScheduler
        GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
        
        scheduler.runDelayed(bukkitPlugin, task -> {
            if (plugin.getAntiBotManager().isSomeModeOnline()) return;
            if (ConfigManger.disableNotificationAfterAttack) {
                Notificator.disableAllNotifications();
            }

            trackerService.onAttackStop();
            ServerUtil.setLastAttack(System.currentTimeMillis());

            scheduler.runDelayed(bukkitPlugin, innerTask -> {
                if (plugin.getAntiBotManager().isSomeModeOnline()) return;
                plugin.getAntiBotManager().getBlackListService().save();
                plugin.getUserDataService().unload();
                plugin.getWhitelist().save();
            }, 20L); // 20 ticks = 1 second
        }, 60L); // 60 ticks = 3 seconds
    }

    @EventHandler
    public void onAttackStart(ModeEnableEvent e) {
        if (e.getEnabledMode() == ModeType.OFFLINE) {
            return;
        }

        trackerService.onNewAttackStart();
    }

    @EventHandler
    public void onIPJoinDuringAttack(DuringAttackIPJoinEvent e){
        bypassDetector.registerJoin();
    }
}

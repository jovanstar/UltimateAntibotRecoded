package me.kr1s_d.ultimateantibot.checks;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import me.kr1s_d.ultimateantibot.UltimateAntiBotSpigot;
import me.kr1s_d.ultimateantibot.common.AttackType;
import me.kr1s_d.ultimateantibot.common.AuthCheckType;
import me.kr1s_d.ultimateantibot.common.IAntiBotManager;
import me.kr1s_d.ultimateantibot.common.IAntiBotPlugin;
import me.kr1s_d.ultimateantibot.common.core.server.CloudConfig;
import me.kr1s_d.ultimateantibot.common.objects.FancyInteger;
import me.kr1s_d.ultimateantibot.common.objects.profile.ConnectionProfile;
import me.kr1s_d.ultimateantibot.common.objects.profile.meta.ScoreTracker;
import me.kr1s_d.ultimateantibot.common.service.VPNService;
import me.kr1s_d.ultimateantibot.common.utils.ConfigManger;
import me.kr1s_d.ultimateantibot.common.utils.MessageManager;
import me.kr1s_d.ultimateantibot.common.utils.ServerUtil;
import me.kr1s_d.ultimateantibot.utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class AuthCheckReloaded {
    private final IAntiBotPlugin plugin;
    private final IAntiBotManager antiBotManager;
    private final Map<String, AuthCheckType> checking;
    private final Map<String, AuthCheckType> completedCheck;
    private final Map<String, FancyInteger> pingMap;
    private final Map<String, Integer> pingData;
    private final Map<String, FancyInteger> failure;
    private final Map<String, ScheduledTask> runningTasks;
    private final Map<String, String> checkInitiator;
    private final VPNService VPNService;
    private final GlobalRegionScheduler scheduler;

    public AuthCheckReloaded(IAntiBotPlugin plugin) {
        this.plugin = plugin;
        this.antiBotManager = plugin.getAntiBotManager();
        this.checking = new HashMap<>();
        this.completedCheck = new HashMap<>();
        this.pingMap = new HashMap<>();
        this.pingData = new HashMap<>();
        this.failure = new HashMap<>();
        this.runningTasks = new HashMap<>();
        this.checkInitiator = new HashMap<>();
        this.VPNService = plugin.getVPNService();
        this.scheduler = Bukkit.getGlobalRegionScheduler();
        plugin.getLogHelper().debug("Loaded " + this.getClass().getSimpleName() + "!");
    }

    public void onPing(ServerListPingEvent e, String ip) {
        if (isCompletingPingCheck(ip)) {
            registerPing(ip);
            if (antiBotManager.isAntiBotModeEnabled()) {
                int currentIPPings = pingMap.get(ip).get();
                int pingRequired = pingData.get(ip);
                if (currentIPPings == pingRequired) {
                    e.setMotd(ServerUtil.colorize(MessageManager.verifiedPingInterface));
                } else {
                    e.setMotd(ServerUtil.colorize(MessageManager.normalPingInterface
                            .replace("$1", String.valueOf(currentIPPings))
                            .replace("$2", String.valueOf(pingRequired))
                    ));
                }
            }
        }

        if (hasExceededPingLimit(ip)) {
            increaseFails(ip, "_unable_to_retrieve_");
            resetData(ip);
        }
    }

    public void onJoin(AsyncPlayerPreLoginEvent e, String ip) {
        if (antiBotManager.getAttackWatcher().getFiredAttacks().contains(AttackType.JOIN_NO_PING) && CloudConfig.a) {
            ConnectionProfile profile = plugin.getUserDataService().getProfile(ip);

            if (profile.getSecondsFromLastPing() <= 60) {
                profile.process(ScoreTracker.ScoreID.AUTH_CHECK_PASS);
                return;
            }
        }

        if (isCompletingPingCheck(ip)) {
            int currentIPPings = pingMap.computeIfAbsent(ip, j -> new FancyInteger(0)).get();
            int pingRequired = pingData.getOrDefault(ip, 0);
            if (pingRequired != 0 && currentIPPings == pingRequired) {
                String initiator = checkInitiator.getOrDefault(ip, null);
                if (initiator != null && initiator.equals(e.getName())) {
                    if (ConfigManger.getProxyCheckConfig().isCheckFastJoin() && !hasFailedThisCheck(ip, 2)) {
                        VPNService.submitIP(ip, e.getName());
                    }
                    addToPingCheckCompleted(ip);
                    checking.remove(ip);
                } else {
                    resetData(ip);
                    increaseFails(ip, e.getName());
                }
            } else if (pingRequired != 0 && currentIPPings < pingRequired) {
                increaseFails(ip, e.getName());
            }
        }

        if (isWaitingResponse(ip)) {
            resetTotal(ip);
            return;
        }

        int checkTimer = ThreadLocalRandom.current().nextInt(ConfigManger.authMinMaxTimer[0], ConfigManger.authMinMaxTimer[1]);
        if (hasCompletedPingCheck(ip)) {
            submitTimerTask(ip, checkTimer);
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ServerUtil.colorize(MessageManager.getTimerMessage(String.valueOf(checkTimer + 1))));
            return;
        }

        int pingTimer = ThreadLocalRandom.current().nextInt(ConfigManger.authMinMaxPing[0], ConfigManger.authMinMaxPing[1]);
        addToCompletingPingCheck(ip, pingTimer);
        checkInitiator.put(ip, e.getName());
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ServerUtil.colorize(MessageManager.getPingMessage(String.valueOf(pingTimer))));
    }

    private boolean hasActiveTimerVerification(String ip) {
        return runningTasks.containsKey(ip);
    }

    private void submitTimerTask(String ip, int timer) {
        if (runningTasks.containsKey(ip)) {
            runningTasks.get(ip).cancel();
        }
        ScheduledTask task = scheduler.runDelayed(
                UltimateAntiBotSpigot.getInstance(),
                t -> {
                    addToWaiting(ip);
                    runningTasks.remove(ip);
                },
                Utils.convertToTicks(1000L * timer),
                TimeUnit.MILLISECONDS
        );
        runningTasks.put(ip, task);
    }

    private boolean hasExceededPingLimit(String ip) {
        if (pingData.get(ip) == null || pingMap.get(ip) == null) {
            return true;
        }
        return pingMap.get(ip).get() > pingData.get(ip);
    }

    private void resetData(String ip) {
        pingMap.remove(ip);
        checking.remove(ip);
        completedCheck.remove(ip);
        ScheduledTask task = runningTasks.remove(ip);
        if (task != null) task.cancel();
        pingData.remove(ip);
    }

    private boolean hasFailedThisCheck(String ip, int min) {
        return failure.getOrDefault(ip, new FancyInteger(0)).get() >= min;
    }

    private void resetTotal(String ip) {
        pingMap.remove(ip);
        checking.remove(ip);
        completedCheck.remove(ip);
        ScheduledTask task = runningTasks.remove(ip);
        if (task != null) task.cancel();
        pingData.remove(ip);
        failure.remove(ip);
    }

    private void addToCompletingPingCheck(String ip, int generatedPingAmount) {
        pingMap.put(ip, new FancyInteger(0));
        pingData.put(ip, generatedPingAmount);
        checking.put(ip, AuthCheckType.PING);
    }

    private boolean hasCompletedPingCheck(String ip) {
        return completedCheck.get(ip) != null && completedCheck.get(ip).equals(AuthCheckType.PING);
    }

    private boolean isCompletingPingCheck(String ip) {
        return checking.get(ip) != null && checking.get(ip).equals(AuthCheckType.PING);
    }

    private boolean isWaitingResponse(String ip) {
        return completedCheck.get(ip) != null && completedCheck.get(ip).equals(AuthCheckType.WAITING);
    }

    private void addToPingCheckCompleted(String ip) {
        completedCheck.put(ip, AuthCheckType.PING);
    }

    private void addToWaiting(String ip) {
        completedCheck.put(ip, AuthCheckType.WAITING);
        plugin.scheduleDelayedTask(() -> {
            completedCheck.remove(ip);
            resetData(ip);
        }, false, ConfigManger.authBetween);
    }

    private void registerPing(String ip) {
        pingMap.computeIfAbsent(ip, j -> new FancyInteger(0)).increment();
    }

    private void increaseFails(String ip, String initiator) {
        failure.computeIfAbsent(ip, j -> new FancyInteger(0)).increment();
        // Here you can handle what happens if too many fails
        // E.g., banning, blacklisting, etc.
    }
}

package com.comicsquid.colorsuppressor.utils;

import com.comicsquid.colorsuppressor.modules.StepCheckered;
import meteordevelopment.meteorclient.utils.player.ChatUtils;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Two-bot step-checker coordination over the loopback TCP transport.
 *
 * Wire protocol (the ONLY messages this addon sends today):
 *   slave -> master: heartbeat, register, "step:<progress>"  (ready/break2done/done/echo/closed)
 *   master -> slave: "step:<command>"                       (build/break2/breakall/
 *                                                                close/closebreak/abort)
 * The legacy multi-slave printing protocol (layer/interval/pause/start/remove/skip/mine/
 * placeStatus/error/finished) was removed with the legacy two-layer engine - nothing
 * sends those messages anymore.
 */
public final class SlaveSystem {
    public static boolean isSlave = false;
    public static ArrayList<String> slaves = new ArrayList<>();
    public static HashMap<String, Boolean> activeSlavesDict = new HashMap<>();
    public static HashMap<String, Boolean> finishedSlavesDict = new HashMap<>();
    public static SlaveTableController tableController = null;
    public static volatile long lastSlaveHeartbeat = 0;            // master-side liveness
    public static String tcpRole = "auto";                         // auto / master / slave (pins TCP roles)

    private static StepCheckered printerModule = null;
    private static String appliedTcpRole = "auto";                 // role the running transport was initialized with

    public static void setupSlaveSystem(StepCheckered module, String ip, int port) {
        printerModule = module;
        if (LocalTCPTransport.isRunning() && tcpRole.equals(appliedTcpRole)) {
            // Transport from a previous activation is still alive with the SAME role (module
            // toggled off/on) - keep it: re-initializing would drop a connected link for no
            // benefit. A role CHANGE must re-initialize, otherwise the pinned role never
            // takes effect while the old transport is alive.
            clearRegistrations();
            ChatUtils.info("§aTCP transport re-used (connection was still alive).");
            return;
        }
        clearRegistrations();
        isSlave = LocalTCPTransport.initialize(ip, port, tcpRole);
        appliedTcpRole = tcpRole;
    }

    private static void clearRegistrations() {
        slaves.clear();
        activeSlavesDict.clear();
        finishedSlavesDict.clear();
    }

    public static void setTcpAddress(String ip, int port) {
        isSlave = LocalTCPTransport.initialize(ip, port, tcpRole);
        appliedTcpRole = tcpRole;
    }

    public static void sendMessageToMaster(String message) {
        if (isSlave) LocalTCPTransport.sendToMaster(message);
    }

    public static void sendToSlave(String username, String message) {
        LocalTCPTransport.sendToSlave(username, message);
    }

    public static void closeConnection() {
        LocalTCPTransport.close();
        if (!isSlave) {
            clearRegistrations();
        }
    }

    public static void handleIncomingTcpMessage(String sender, String rawMessage) {
        // If no sender is specified, the message is from master
        if (rawMessage == null || rawMessage.isBlank()) return;
        String[] colonSplit = rawMessage.replace(" ", "").split(":");
        String command = colonSplit[0];

        // Master -> Client (the slave side)
        if (sender == null) {
            if (printerModule == null) return;
            switch (command) {
                case "step":
                    if (colonSplit.length >= 2) {
                        printerModule.onStepMasterCommand(rawMessage.substring(rawMessage.indexOf(':') + 1));
                    }
                    break;
                default:
                    ChatUtils.warning("Unknown command: '" + command + "'");
                    break;
            }
            return;
        }

        // Client -> Master (the master side)
        switch (command) {
            case "heartbeat":
                lastSlaveHeartbeat = System.currentTimeMillis();
                // A module re-toggle on the master clears the registered-slave list while the
                // TCP transport stays alive - the slave never re-connects (its link is fine),
                // so it never re-registers and the run can never start again (slaves.size()
                // gate). Any live heartbeat must (re-)register its sender.
                if (!slaves.contains(sender)) {
                    registerSlave(sender);
                    ChatUtils.info("§aRegistered slave: " + sender + " (heartbeat) Total slaves: §b" + slaves.size());
                }
                break;
            case "register":
                if (!slaves.contains(sender)) {
                    registerSlave(sender);
                    lastSlaveHeartbeat = System.currentTimeMillis();
                    ChatUtils.info("§aRegistered slave: " + sender + " Total slaves: §b" + slaves.size());
                }
                break;
            case "step":
                if (printerModule != null && colonSplit.length >= 2) {
                    printerModule.onStepSlaveMessage(rawMessage.substring(rawMessage.indexOf(':') + 1));
                }
                break;
            default:
                ChatUtils.warning("Unknown command: '" + command + "'");
                break;
        }
    }

    private static void registerSlave(String sender) {
        slaves.add(sender);
        finishedSlavesDict.put(sender, true);
        activeSlavesDict.put(sender, false);
        if (tableController != null) tableController.rebuild();
    }
}

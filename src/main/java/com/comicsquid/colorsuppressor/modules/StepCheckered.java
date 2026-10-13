package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.Addon;
import com.comicsquid.colorsuppressor.interfaces.MapPrinter;
import com.comicsquid.colorsuppressor.utils.*;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.utils.StarscriptTextBoxRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.item.map.MapState;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.lang3.tuple.Triple;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class StepCheckered extends Module implements MapPrinter {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMining = settings.createGroup("Mining & Collecting", false);
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced", false);
    private final SettingGroup sgMultiUser = settings.createGroup("Multi User", false);
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    // Legacy (parked) engine line width - fixed at 2, not a setting anymore
    private static final int linesPerRun = 2;

    private final Setting<Double> interactionRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("interaction-range")
        .description("The maximum range you can place blocks around yourself.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place per game tick (default 4, some servers allow more).")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> breaksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("breaks-per-tick")
        .description("How many blocks to break per game tick (default 4, some servers allow more).")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<List<Block>> startBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("start-blocks")
        .description("Which block to interact with to start the printing process.")
        .defaultValue(Blocks.STONE_BUTTON, Blocks.ACACIA_BUTTON, Blocks.BAMBOO_BUTTON, Blocks.BIRCH_BUTTON,
            Blocks.CRIMSON_BUTTON, Blocks.DARK_OAK_BUTTON, Blocks.JUNGLE_BUTTON, Blocks.OAK_BUTTON,
            Blocks.POLISHED_BLACKSTONE_BUTTON, Blocks.SPRUCE_BUTTON, Blocks.WARPED_BUTTON)
        .build()
    );

    private final Setting<Block> fillerBlock = sgGeneral.add(new BlockSetting.Builder()
        .name("filler-block")
        .description("The block to use as a filler.")
        .defaultValue(Blocks.RESIN_BLOCK)
        .build()
    );


    private final Setting<SprintMode> sprinting = sgGeneral.add(new EnumSetting.Builder<SprintMode>()
        .name("sprint-mode")
        .description("How to sprint.")
        .defaultValue(SprintMode.Always)
        .build()
    );

    private final Setting<Boolean> activationReset = sgGeneral.add(new BoolSetting.Builder()
        .name("activation-reset")
        .description("Disable if the bot should continue after reconnecting to the server.")
        .defaultValue(true)
        .build()
    );

    // rotate-place setting removed: 6b6t anticheat is soft, allows placing/breaking
    // without rotation. All placement and breaking goes forward without rotating.

    private final Setting<Boolean> customFolderPath = sgGeneral.add(new BoolSetting.Builder()
        .name("custom-folder-path")
        .description("Allows to set a custom path to the nbt folder.")
        .defaultValue(false)
        .onChanged((value) -> warnPathChanged())
        .build()
    );

    public final Setting<String> mapPrinterFolderPath = sgGeneral.add(new StringSetting.Builder()
        .name("color-suppressor-folder-path")
        .description("The path to your color-suppressor directory.")
        .defaultValue("C:\\Users\\(username)\\AppData\\Roaming\\.minecraft\\color-suppressor")
        .wide()
        .renderer(StarscriptTextBoxRenderer.class)
        .visible(() -> customFolderPath.get())
        .onChanged((value) -> warnPathChanged())
        .build()
    );

    private final Setting<Boolean> useDefaultConfigFile = sgGeneral.add(new BoolSetting.Builder()
        .name("use-default-config-file")
        .description("Automatically load the config file below when the module is enabled.")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> configFileName = sgGeneral.add(new StringSetting.Builder()
        .name("config-file-name")
        .description("The config file that is loaded  when the module is enabled.")
        .defaultValue("step-checkered-config.json")
        .wide()
        .renderer(StarscriptTextBoxRenderer.class)
        .visible(() -> useDefaultConfigFile.get())
        .build()
    );

    // Mining & Collecting

    private final Setting<Double> durabilityBuffer = sgMining.add(new DoubleSetting.Builder()
        .name("durability-buffer")
        .description("The additional required durability for restocked mining tools on top of the predicted one (in %).")
        .defaultValue(0.1)
        .min(0)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<Integer> minDurability = sgMining.add(new IntSetting.Builder()
        .name("min-durability")
        .description("The minimum absolute durability value allowed on a mining tools before exchanging.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 50)
        .build()
    );

    private final Setting<Integer> masterHelpMinLines = sgMining.add(new IntSetting.Builder()
        .name("master-help-min-lines")
        .description("The minimum lines left to mine required so the master decides to join mining after locking the map.")
        .defaultValue(4)
        .min(1)
        .max(64)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> collectBlocks = sgMining.add(new BoolSetting.Builder()
        .name("collect-mined-blocks")
        .description("Collect the dropped block items again while mining.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> collectAllDirections = sgMining.add(new BoolSetting.Builder()
        .name("collect-both-directions")
        .description("If set to false, only collect items when going south-to-north and ignore them otherwise. " +
                    "This could lead to a speedup at the risk of despawning items.")
        .defaultValue(true)
        .visible(() -> collectBlocks.get())
        .build()
    );

    // Advanced

    private final Setting<Integer> preRestockDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("pre-restock-delay")
        .description("How many ticks to wait to take items after opening the chest.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> invActionDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("inventory-action-delay")
        .description("How many ticks to wait between each inventory action (moving a stack).")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> postRestockDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("post-restock-delay")
        .description("How many ticks to wait after restocking.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> preSwapDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("pre-swap-delay")
        .description("How many ticks to wait before swapping an item into the hotbar.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> postSwapDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("post-swap-delay")
        .description("How many ticks to wait after swapping an item into the hotbar.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> retryInteractTimer = sgAdvanced.add(new IntSetting.Builder()
        .name("retry-interact-timer")
        .description("How many ticks to wait for chest response before interacting with it again.")
        .defaultValue(80)
        .min(1)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Integer> mapLoadTimeout = sgAdvanced.add(new IntSetting.Builder()
        .name("map-load-timeout")
        .description("How many ticks to wait to update the map in hand.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Integer> posResetTimeout = sgAdvanced.add(new IntSetting.Builder()
        .name("pos-reset-timeout")
        .description("How many ticks to wait after the player position was reset by the server.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Double> checkpointBuffer = sgAdvanced.add(new DoubleSetting.Builder()
        .name("checkpoint-buffer")
        .description("The buffer area of the checkpoints. Larger means less precise walking, but might be desired at higher speeds.")
        .defaultValue(0.2)
        .min(0)
        .sliderRange(0, 1)
        .build()
    );

//    private final Setting<Boolean> fixErrors = sgAdvanced.add(new BoolSetting.Builder()
//        .name("fix-errors")
//        .description("Fix errors by mining the misplaced blocks on the fly.")
//        .defaultValue(false)
//        .build()
//    );

    private final Setting<Boolean> snapToCheckpoints = sgAdvanced.add(new BoolSetting.Builder()
        .name("snap-to-checkpoints")
        .description("Snap to checkpoints when getting close.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> moveToFinishedFolder = sgAdvanced.add(new BoolSetting.Builder()
        .name("move-to-finished-folder")
        .description("Moves finished NBT files into the finished-maps folder in the color-suppressor folder.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnFinished = sgAdvanced.add(new BoolSetting.Builder()
        .name("disable-on-finished")
        .description("Disables the printer when all nbt files are finished.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugPrints = sgAdvanced.add(new BoolSetting.Builder()
        .name("debug-prints")
        .description("Prints additional information.")
        .defaultValue(false)
        .build()
    );

    // Multi User

    private final Setting<String> tcpIp = sgMultiUser.add(new StringSetting.Builder()
        .name("TCP-IP")
        .description("The loopback IP used by bot-to-bot TCP communication.")
        .defaultValue("127.0.0.1")
        .onChanged(value -> updateTcpAddress())
        .build()
    );

    private final Setting<Integer> tcpPort = sgMultiUser.add(new IntSetting.Builder()
        .name("TCP-port")
        .description("The loopback TCP port used by bot-to-bot communication.")
        .defaultValue(42069)
        .min(1)
        .max(65535)
        .sliderRange(1, 65535)
        .onChanged(value -> updateTcpAddress())
        .build()
    );

    private enum BotRole {
        empty,
        master,
        slave
    }

    private final Setting<BotRole> botRole = sgMultiUser.add(new EnumSetting.Builder<BotRole>()
        .name("bot-role")
        .description("master: this bot hosts the TCP server and walks the checkpoints. slave: this bot builds/breaks. Must be set on both instances before starting.")
        .defaultValue(BotRole.empty)
        .build()
    );

    private final Setting<String> masterUsername = sgMultiUser.add(new StringSetting.Builder()
        .name("master-username")
        .description("Username of the account that acts as MASTER. Each bot self-identifies by its username - set this on both instances. Overrides bot-role.")
        .defaultValue("")
        .onChanged(value -> updateTcpAddress())
        .build()
    );

    private final Setting<String> slaveUsername = sgMultiUser.add(new StringSetting.Builder()
        .name("slave-username")
        .description("Username of the account that acts as SLAVE. Each bot self-identifies by its username - set this on both instances. Overrides bot-role.")
        .defaultValue("")
        .onChanged(value -> updateTcpAddress())
        .build()
    );

    //Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the selected areas.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderMap = sgRender.add(new BoolSetting.Builder()
        .name("render-map")
        .description("Highlights the placement plan of the selected batch (what the slave will build).")
        .defaultValue(false)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Integer> renderBatch = sgRender.add(new IntSetting.Builder()
        .name("render-batch")
        .description("The batch index (0-62) whose placement plan render-map highlights. Follows the engine's current batch once runs start.")
        .defaultValue(0)
        .min(0)
        .max(62)
        .sliderRange(0, 62)
        .visible(() -> render.get() && renderMap.get())
        .build()
    );

    private final Setting<Boolean> renderNextBlockPos = sgRender.add(new BoolSetting.Builder()
        .name("render-next-block-pos")
        .description("Highlights the block position of the current place/mining action.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Boolean> renderChestPositions = sgRender.add(new BoolSetting.Builder()
        .name("render-chest-positions")
        .description("Highlights the selected chests.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Boolean> renderOpenPositions = sgRender.add(new BoolSetting.Builder()
        .name("render-open-positions")
        .description("Indicate the position the bot will go to in order to interact with the chest.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Boolean> renderCheckpoints = sgRender.add(new BoolSetting.Builder()
        .name("render-checkpoints")
        .description("Indicate the checkpoints the bot will traverse.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Boolean> renderCheckpointGrid = sgRender.add(new BoolSetting.Builder()
        .name("render-checkpoint-grid")
        .description("Highlights ALL 325 master platform checkpoints (x=125+2j, z=14/40/64/88/112). The 5 checkpoints of the rendered batch's sweep lane use the next-checkpoints color; the fill spot and cartography lock position use the special-checkpoints color.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<SettingColor> nextCheckpointsColor = sgRender.add(new ColorSetting.Builder()
        .name("next-checkpoints-color")
        .description("Color for the 5 checkpoints of the rendered batch's sweep lane (follows render-batch).")
        .defaultValue(new SettingColor(255, 165, 0, 190))
        .visible(() -> render.get() && renderCheckpointGrid.get())
        .build()
    );

    private final Setting<SettingColor> specialCheckpointsColor = sgRender.add(new ColorSetting.Builder()
        .name("special-checkpoints-color")
        .description("Color for the master's special points: the map fill spot (entry) and the cartography lock position.")
        .defaultValue(new SettingColor(255, 70, 70, 190))
        .visible(() -> render.get() && renderCheckpointGrid.get())
        .build()
    );

    private final Setting<Boolean> renderSpecialInteractions = sgRender.add(new BoolSetting.Builder()
        .name("render-special-interactions")
        .description("Indicate the position where the reset button and cartography table will be used.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<Double> indicatorSize = sgRender.add(new DoubleSetting.Builder()
        .name("indicator-size")
        .description("How big the rendered indicator will be.")
        .defaultValue(0.13)
        .min(0)
        .sliderRange(0, 1)
        .visible(() -> render.get())
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("The render color.")
        .defaultValue(new SettingColor(22, 230, 206, 155))
        .visible(() -> render.get())
        .build()
    );

    int timeoutTicks;
    int interactTimeout;
    int toBeSwappedSlot;
    int lastPlacedLine;
    int minedLines;
    int suppressedLines;
    int lastReportedZ;
    int awaitSuppressionZ;
    int heartbeatTicks;
    boolean pendingDisable;                                          // deferred toggle (see requestDisable)
    ArrayList<HashMap<BlockPos, Block>> stepRecLayers;               // pair j -> (local pos (x, y0/y1, z -1..127)) -> block
    ArrayList<HashMap<BlockPos, Block>> stepDomLayers;
    HashMap<BlockPos, Block> renderedBatchPlan;                      // cached render-map plan
    String renderedBatchKey;
    int currentBatch = -1;                                           // batch being built (slave)
    int batchPassRetries;
    int breakStage;                                                  // 0 = printing, 2 = break(2), 4 = break-all
    boolean printRepairPending;                                      // print pass ended with missing blocks
    boolean collectingStragglers;                                    // exit-verification item sweep active
    int sweepBudget;                                                 // tick budget for the straggler sweep
    volatile boolean startBuildingRequested;                         // START ALL deferred trigger
    boolean fullSweepActive;                                         // full-area RESET sweep in progress
    int fullSweepBudget;                                             // tick budget for the full-area sweep
    boolean fullSweepCenterChecked;                                  // center vantage already scanned
    int restockCooldown;                                             // blocks repeat supply-run triggers
    HashMap<BlockPos, Integer> placementCooldowns;                   // litematica-style: retry timer per position
    volatile boolean break2Permitted;                                // master calls (TCP "step:break2")
    volatile boolean breakAllPermitted;                              // master calls (TCP "step:breakall")
    volatile boolean masterBatchBuilt;                               // slave→master: batch k fully printed
    volatile boolean masterBatchDone;                                // slave→master: batch k fully recycled
    int masterDwellTicks;                                            // dwell timer for checkpoint walks
    HashMap<BlockPos, Block> currentBuildPlan;                       // the slave's active placement plan
    long lastTickTime;
    boolean closeNextInvPacket;
    boolean workOnUpper;
    boolean masterMiningReady;
    State state;
    State oldState;
    State debugPreviousState;
    String debugLastAction;
    ChestRole pendingRole;
    Pair<Integer, Integer> workingInterval;                         // Interval the bot should work in 0-127
    Pair<BlockPos, Vec3d> cartographyTable;
    Pair<BlockPos, Vec3d> finishedMapChest;
    Pair<BlockPos, Vec3d> anvil;
    Pair<BlockPos, Vec3d> foodChest;
    Pair<BlockPos, Vec3d> xpBottleChest;
    ArrayList<Pair<BlockPos, Vec3d>> mapMaterialChests;
    BlockPos tempChestPos;
    BlockPos lastInteractedChest;
    BlockPos nextBlockPos;
    Item lastSwappedMaterial;
    InventoryS2CPacket toBeHandledInvPacket;
    HashMap<Integer, Pair<Block, Integer>> blockPaletteDict;        // Maps palette block id to the Minecraft block and amount
    HashMap<Item, ArrayList<Pair<BlockPos, Vec3d>>> materialDict;   // Maps block to the chest pos and the open position
    Set<ItemStack> toolSet;                                         // Set of all registered tool item stacks
    ArrayList<Integer> availableSlots;
    ArrayList<Integer> availableHotBarSlots;
    ArrayList<Integer> lowerMiningCommand;
    ArrayList<Integer> upperMiningCommand;
    ArrayList<Triple<Item, Integer, Integer>> restockList;          // Material, Stacks, Raw Amount
    ArrayList<BlockPos> checkedChests;
    ArrayList<Pair<Vec3d, Pair<String, BlockPos>>> checkpoints;     // (GoalPos, (checkpointAction, targetBlock))
    ArrayList<File> startedFiles;
    ArrayList<Integer> restockBacklogSlots;
    ArrayList<Integer> depositBacklogSlots;
    HashSet<BlockPos> fullDepositChests;                             // chests that ran out of room this batch
    ArrayList<String> switchedToMiningSlaves;
    private final ArrayDeque<String> pendingStepCommands = new ArrayDeque<>();
    private volatile int stepSlaveReadyBatch = -1;
    private volatile int stepSlaveBreak2DoneBatch = -1;
    private volatile int stepSlaveDoneBatch = -1;
    private volatile boolean stepSlaveReadyPriming;
    private volatile boolean stepSlaveReadyClosing;
    private volatile boolean stepSlaveClosingDone;
    private volatile StepMasterStage stepMasterStage = StepMasterStage.IDLE;
    private int stepMasterBatch = -1;
    private int stepMasterDwellTicks;
    private int stepMasterWaitTicks;
    private boolean stepMasterSweepCompletionPending;
    private boolean stepSweepQueuePending;
    private int stepPendingSweepLane;
    private int stepPendingSweepStart;
    private int stepPendingSweepEnd;
    private boolean stepMasterBreak2Sent;
    private boolean stepMasterBreakAllSent;
    private boolean stepMasterCloseBreakSent;
    private int stepWorkingMapId = -1;
    private boolean stepMapSwapPending;
    private int stepLockStep = -1;
    private int stepLockDelay;
    File mapFolder;
    File mapFile;
    BlockPos lowerMapCorner;
    BlockPos upperMapCorner;
    Block[][][] lowerMapLayer;
    Block[][][] upperMapLayer;

    private static final int[] STEP_CHECKPOINT_Z = {14, 40, 64, 88, 112};
    private static final int STEP_DWELL_TICKS = 80;

    public StepCheckered() {
        super(Addon.CATEGORY, "step-checkered", "Uses the step-checker method to build colour-suppression maps.");
        // Local-only supervisor hook: if the (git-ignored) BotControlEndpoint tool is on the
        // classpath, start it with this module as controller. Fresh clones without the local
        // tool compile and run fine - this hook is simply skipped.
        try {
            Class.forName("com.comicsquid.colorsuppressor.utils.BotControlEndpoint")
                .getMethod("startFor", StepCheckered.class)
                .invoke(null, this);
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException e) {
            Addon.LOG.warn("Failed to start the local control endpoint", e);
        }
    }

    /** Snapshot for the local control tool's /status - safe to read from any thread. */
    public String controlStatusJson() {
        String player = mc != null && mc.player != null ? mc.getSession().getUsername() : "unknown";
        String stateName = state == null ? "uninitialized" : state.name();
        long hbAge = SlaveSystem.lastSlaveHeartbeat > 0 ? System.currentTimeMillis() - SlaveSystem.lastSlaveHeartbeat : -1;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"player\":\"").append(player).append("\",");
        sb.append("\"role\":\"").append(SlaveSystem.isSlave ? "slave" : "master").append("\",");
        sb.append("\"moduleActive\":").append(isActive()).append(',');
        sb.append("\"state\":\"").append(stateName).append("\",");
        sb.append("\"stepMasterStage\":\"").append(stepMasterStage).append("\",");
        sb.append("\"workingMapId\":").append(stepWorkingMapId).append(',');
        sb.append("\"batch\":").append(stepMasterBatch).append(',');
        sb.append("\"slaveConnected\":").append(!SlaveSystem.slaves.isEmpty()).append(',');
        sb.append("\"heartbeatAgeMs\":").append(hbAge);
        if (mc.player != null) {
            ItemStack handStack = mc.player.getMainHandStack();
            String handName = handStack.getItem().toString();
            if (handStack.getItem() instanceof FilledMapItem) {
                handName = "filled_map#" + handStack.get(DataComponentTypes.MAP_ID);
            }
            sb.append(",\"mainHand\":\"").append(handName).append('"');
            // Live pixel dump of the held map - the client receives every pixel update while
            // holding it, so this is the rendered ground truth with no server save needed
            MapState ms = Utils.getHeldMapState();
            if (ms != null) {
                byte[] cols = new byte[ms.colors.length];
                for (int i = 0; i < ms.colors.length; i++) cols[i] = ms.colors[i];
                sb.append(",\"mapPixels\":\"").append(java.util.Base64.getEncoder().encodeToString(cols)).append('"');
            }
        }
        String pos = mc != null && mc.player != null
            ? String.format(java.util.Locale.ROOT, "\"x\":%.1f,\"y\":%.1f,\"z\":%.1f",
                mc.player.getX(), mc.player.getY(), mc.player.getZ())
            : "\"x\":null,\"y\":null,\"z\":null";
        sb.append(',').append(pos);
        // Live diagnosis: the exact block the interaction retry loop is hammering on
        if (lastInteractedChest != null) {
            sb.append(",\"lastChest\":\"").append(lastInteractedChest.toShortString()).append('"');
            sb.append(",\"sneaking\":").append(mc.player != null && mc.player.isSneaking());
            sb.append(",\"screenOpen\":").append(mc.currentScreen != null);
        }
        sb.append('}');
        return sb.toString();
    }

    /** Remote start (local control tool): enable the module; if idle, begin the run. */
    public void controlStart() {
        if (mc == null || mc.player == null || mc.world == null) {
            info("Control endpoint: start ignored - not in game yet");
            return;
        }
        if (!isActive()) {
            info("Control endpoint: enabling module");
            toggle();
            return;
        }
        if (state == State.SelectingChests) {
            info("Control endpoint: starting the run");
            startBuilding();
        }
    }

    /** Remote stop (local control tool). */
    public void controlStop() {
        if (isActive()) toggle();
    }

    /** Local supervisor hook for the map-hold safety switch. */
    public void controlHoldMap(boolean on) {
        if (SlaveSystem.isSlave) return;
        if (on) {
            if (stepWorkingMapId < 0) stepPinWorkingMap();
            if (stepWorkingMapId >= 0 && !stepEnsureMapHeld()) {
                info("Working map selection queued from the inventory.");
            }
        } else {
            stepStowMap();
        }
    }

    /**
     * Start the configured cartography-table lock machine after the final map walk.  The
     * working map is selected by pinned map id; named reference maps are never accepted.
     */
    public void requestLockMap() {
        if (SlaveSystem.isSlave || stepMasterStage != StepMasterStage.READY_TO_LOCK) {
            warning("Lock is available only after the master reaches READY_TO_LOCK.");
            return;
        }
        if (cartographyTable == null) {
            error("No cartography table is configured for the locked-map end flow.");
            return;
        }
        stepLockStep = 0;
        stepLockDelay = 0;
        stepMasterStage = StepMasterStage.LOCK_WALK;
        checkpoints.clear();
        checkpoints.add(new Pair<>(cartographyTable.getRight(), new Pair<>("stepLockTable", null)));
        state = State.Walking;
        stepStowMap();
        info("§aWalking to the configured cartography table to lock the pinned map.");
    }

    /** Manual break triggers retained for isolated slave phase testing. */
    public void onControlCommand(String command) {
        if ("break2".equals(command) && state == State.AwaitBreakPermission2) {
            info("§aMaster call received: break(2) - taking down Y2/Y3 of lanes " + (2 * currentBatch) + "," + (2 * currentBatch + 1));
            break2Permitted = true;
        } else if ("breakall".equals(command) && state == State.AwaitBreakPermissionAll) {
            info("§aMaster call received: break-all for batch " + currentBatch + ".");
            breakAllPermitted = true;
        } else {
            info("Control: command '" + command + "' ignored in state " + state);
        }
    }

    /**
     * Master-to-slave step-checker command.  TCP reader threads must not touch Minecraft state,
     * so the command is queued and consumed on the client tick in processPendingStepCommands().
     */
    public void onStepMasterCommand(String command) {
        if (command == null || command.isBlank()) return;
        if (debugPrints.get()) info("§3[dbg] TCP master→slave: §b" + command);
        // ABORT clears the entire command queue and puts itself at the head — a stuck
        // "build" command at the front would otherwise block it from ever being processed
        // (the slave's processPendingStepCommands only peeks at the HEAD of the queue,
        // so a mid-phase build that can't be consumed traps everything behind it)
        if ("abort".equals(command.split(":")[0])) {
            synchronized (pendingStepCommands) {
                pendingStepCommands.clear();
                pendingStepCommands.addLast(command);
            }
            return;
        }
        synchronized (pendingStepCommands) {
            pendingStepCommands.addLast(command);
        }
    }

    /**
     * Slave-to-master progress message.  This method only records volatile flags; the master
     * scheduler consumes them on the client thread.  The protocol mirrors the proven manual
     * runner: ready -> break(2) complete -> batch recycled.
     */
    public void onStepSlaveMessage(String message) {
        if (message == null || message.isBlank()) return;
        if (debugPrints.get()) info("§3[dbg] TCP slave→master: §b" + message);
        String[] parts = message.split(":");
        if (parts.length < 2) return;
        switch (parts[0]) {
            case "ready" -> {
                if ("prime".equals(parts[1])) {
                    stepSlaveReadyPriming = true;
                } else if ("close".equals(parts[1])) {
                    stepSlaveReadyClosing = true;
                } else {
                    Integer batch = parseStepBatch(parts[1]);
                    if (batch != null) stepSlaveReadyBatch = batch;
                }
            }
            case "break2done" -> {
                Integer batch = parseStepBatch(parts.length > 1 ? parts[1] : null);
                if (batch != null) stepSlaveBreak2DoneBatch = batch;
            }
            case "done" -> {
                Integer batch = parseStepBatch(parts.length > 1 ? parts[1] : null);
                if (batch != null) stepSlaveDoneBatch = batch;
            }
            case "closed" -> stepSlaveClosingDone = true;
            default -> {
                if (debugPrints.get()) info("Unknown step progress message: " + message);
            }
        }
    }

    private Integer parseStepBatch(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public void onActivate() {
        lastTickTime = System.currentTimeMillis();
        if (!activationReset.get() && checkpoints != null) {
            return;
        }
        materialDict = new HashMap<>();
        availableSlots = new ArrayList<>();
        availableHotBarSlots = new ArrayList<>();
        restockList = new ArrayList<>();
        toolSet = new HashSet<>();
        checkedChests = new ArrayList<>();
        checkpoints = new ArrayList<>();
        startedFiles = new ArrayList<>();
        restockBacklogSlots = new ArrayList<>();
        depositBacklogSlots = new ArrayList<>();
        fullDepositChests = new HashSet<>();
        lowerMiningCommand = new ArrayList<>();
        upperMiningCommand = new ArrayList<>();
        switchedToMiningSlaves = new ArrayList<>();
        lowerMapCorner = null;
        upperMapCorner = null;
        lastInteractedChest = null;
        nextBlockPos = null;
        cartographyTable = null;
        finishedMapChest = null;
        anvil = null;
        foodChest = null;
        xpBottleChest = null;
        pendingRole = null;
        mapMaterialChests = new ArrayList<>();
        lastSwappedMaterial = null;
        toBeHandledInvPacket = null;
        closeNextInvPacket = false;
        workOnUpper = false;
        masterMiningReady = false;
        timeoutTicks = 0;
        interactTimeout = 0;
        toBeSwappedSlot = -1;
        minedLines = 128;
        suppressedLines = -1;
        lastReportedZ = -1;
        heartbeatTicks = 0;
        pendingDisable = false;
        placementCooldowns = new HashMap<>();
        synchronized (pendingStepCommands) {
            pendingStepCommands.clear();
        }
        stepSlaveReadyBatch = -1;
        stepSlaveBreak2DoneBatch = -1;
        stepSlaveDoneBatch = -1;
        stepSlaveReadyPriming = false;
        stepSlaveReadyClosing = false;
        stepSlaveClosingDone = false;
        stepMasterStage = StepMasterStage.IDLE;
        stepMasterBatch = -1;
        stepMasterDwellTicks = 0;
        stepMasterWaitTicks = 0;
        stepMasterSweepCompletionPending = false;
        stepSweepQueuePending = false;
        stepPendingSweepLane = -1;
        stepPendingSweepStart = -1;
        stepPendingSweepEnd = -1;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        stepMasterCloseBreakSent = false;
        stepWorkingMapId = -1;
        stepMapSwapPending = false;
        stepLockStep = -1;
        stepLockDelay = 0;
        currentBatch = -1;
        currentBuildPlan = null;
        breakStage = 0;
        printRepairPending = false;
        break2Permitted = false;
        breakAllPermitted = false;
        stepRecLayers = new ArrayList<>();
        stepDomLayers = new ArrayList<>();
        oldState = null;
        debugPreviousState = null;
        lowerMapLayer = new Block[128][129][2];
        upperMapLayer = new Block[128][129][2];

        setInterval(new Pair<>(0, 127));
        // Role must be explicitly selected (master or slave) before the module can run
        if (botRole.get() == BotRole.empty && masterUsername.get().isBlank() && slaveUsername.get().isBlank()) {
            error("Select a bot-role (master or slave) in the Multi User settings before starting.");
            requestDisable();
            return;
        }
        SlaveSystem.tcpRole = resolveTcpRole();
        SlaveSystem.setupSlaveSystem(this, tcpIp.get(), tcpPort.get());

        if (!customFolderPath.get()) {
            mapFolder = new File(Utils.getMinecraftDirectory(), "color-suppressor");
        } else {
            mapFolder = new File(mapPrinterFolderPath.get());
        }
        if (!Utils.createFolders(mapFolder)) {
            toggle();
            return;
        }

        if (!prepareNextMapFile()) return;
        info("§aBuilding: §b" + mapFile.getName());

        state = State.SelectingLowerMapArea;
        if (useDefaultConfigFile.get()) {
            File configFolder = new File(mapFolder, "_configs");
            if (!loadConfig(new File(configFolder, configFileName.get()))) {
                info("§aSelect the §bLower Map Building Area (128x128)§a. (Right-click anywhere on the floor)");
            }
        } else {
            info("§aSelect the §bLower Map Building Area (128x128)§a. (Right-click anywhere on the floor)");
        }
    }

    @Override
    public void onDeactivate() {
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        if (!SlaveSystem.isSlave && stepMasterStage != StepMasterStage.IDLE) {
            stepStowMap();
            stepMasterStage = StepMasterStage.ABORTED;
            checkpoints.clear();
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerInteractBlockC2SPacket packet) || state == null) return;
        switch (state) {
            case SelectingLowerMapArea:
                BlockPos hitPos = packet.getBlockHitResult().getBlockPos().offset(packet.getBlockHitResult().getSide());
                int adjustedX = Utils.getIntervalStart(hitPos.getX());
                // Corner = pixel (0,0) = the map tile's north-west block (doc §2): the 128x128
                // map area starts AT the corner; the noobline sits one block north of it
                // (outside the tile) and is part of the batch plans via converter z = -1
                int adjustedZ = Utils.getIntervalStart(hitPos.getZ());
                lowerMapCorner = new BlockPos(adjustedX, hitPos.getY(), adjustedZ);
                MapAreaCache.reset(lowerMapCorner, true);
                // The step-checker print box is 4 tall (each batch stacks the next pair 2 above
                // the current one) - the upper corner is always the lower corner + 2
                upperMapCorner = lowerMapCorner.up(2);
                verifyBatchAssembly();
                state = State.SelectingTable;
                info("§aLower Map Area selected. §7Upper auto-selected (2 above - the print box is 4 tall).");
                info("§aSelect the §bCartography Table.");
                return;
            case SelectingTable:
                BlockPos blockPos = packet.getBlockHitResult().getBlockPos();
                if (MapAreaCache.getCachedBlockState(blockPos).getBlock().equals(Blocks.CARTOGRAPHY_TABLE)) {
                    cartographyTable = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("§aCartography Table selected. Select the §bFinished Map Chest.");
                    state = State.SelectingFinishedMapChest;
                }
                break;
            case SelectingFinishedMapChest:
                blockPos = packet.getBlockHitResult().getBlockPos();
                if (MapAreaCache.getCachedBlockState(blockPos).getBlock() instanceof AbstractChestBlock) {
                    finishedMapChest = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("§aFinished Map Chest selected. Select the §bAnvil.");
                    state = State.SelectingAnvil;
                }
                break;
            case SelectingAnvil:
                blockPos = packet.getBlockHitResult().getBlockPos();
                if (MapAreaCache.getCachedBlockState(blockPos).getBlock() instanceof AnvilBlock) {
                    anvil = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("§aAnvil selected. Select the §bFood Chest.");
                    state = State.SelectingFoodChest;
                }
                break;
            case SelectingFoodChest:
            case SelectingXpChest:
                blockPos = packet.getBlockHitResult().getBlockPos();
                if (MapAreaCache.getCachedBlockState(blockPos).getBlock().equals(Blocks.CHEST)) {
                    tempChestPos = blockPos;
                    pendingRole = state == State.SelectingFoodChest ? ChestRole.Food : ChestRole.Xp;
                    state = State.AwaitRegisterResponse;
                }
                break;
            case SelectingChests:
                if (startBlocks.get().isEmpty())
                    warning("No block selected as Start Block! Please select one in the settings.");
                blockPos = packet.getBlockHitResult().getBlockPos();
                BlockState blockState = MapAreaCache.getCachedBlockState(blockPos);
                if (MapAreaCache.getCachedBlockState(blockPos).getBlock().equals(Blocks.CHEST)) {
                    tempChestPos = blockPos;
                    pendingRole = ChestRole.Material;
                    state = State.AwaitRegisterResponse;
                }
                if (startBlocks.get().contains(blockState.getBlock())) startBuilding();
                break;
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (state == null) return;

        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            timeoutTicks = posResetTimeout.get();
            if (timeoutTicks > 0) Utils.setForwardPressed(false);
        }

        if (!(event.packet instanceof InventoryS2CPacket packet)) return;

        if (state.equals(State.AwaitRegisterResponse)) {
            LinkedHashSet<Item> itemTypes = new LinkedHashSet<>();
            ArrayList<ItemStack> toolStacks = new ArrayList<>();
            boolean hasBundle = false;
            boolean hasFood = false;
            boolean hasXpBottles = false;
            for (int i = 0; i < packet.contents().size() - 36; i++) {
                ItemStack stack = packet.contents().get(i);
                if (stack.isEmpty()) continue;
                Item item = stack.getItem().asItem();
                if (item == Items.BUNDLE) {
                    hasBundle = true;
                    continue;
                }
                // Any chest containing maps or panes registers as the Map Chest
                if (pendingRole == ChestRole.Material && (item == Items.MAP || item == Items.GLASS_PANE)) {
                    info("§aRegistered §bMap Chest");
                    mapMaterialChests = Utils.saveAdd(mapMaterialChests, tempChestPos, mc.player.getEntityPos());
                    state = State.SelectingChests;
                    return;
                }
                itemTypes.add(item);
                if (stack.contains(DataComponentTypes.FOOD)) hasFood = true;
                if (item == Items.EXPERIENCE_BOTTLE) hasXpBottles = true;
                if (ToolUtils.isTool(stack)) toolStacks.add(stack);
            }
            if (hasBundle) warning("Bundle in chest: the bot cannot see or use items inside bundles - unpack them, then re-open the chest.");

            switch (pendingRole) {
                case Food -> {
                    if (!hasFood) {
                        warning("No food found in chest.");
                        state = State.SelectingFoodChest;
                        return;
                    }
                    foodChest = new Pair<>(tempChestPos, mc.player.getEntityPos());
                    info("§aFood Chest selected. Select the §bXP Bottle Chest.");
                    state = State.SelectingXpChest;
                }
                case Xp -> {
                    if (!hasXpBottles) {
                        warning("No XP bottles found in chest.");
                        state = State.SelectingXpChest;
                        return;
                    }
                    xpBottleChest = new Pair<>(tempChestPos, mc.player.getEntityPos());
                    info("§aXP Bottle Chest selected. Select all §bMaterial-, Tool-, and Map-Chests.");
                    state = State.SelectingChests;
                }
                case Material -> {
                    if (itemTypes.isEmpty()) {
                        warning("No items found in chest.");
                        state = State.SelectingChests;
                        return;
                    }
                    // Multiple block types per chest are allowed: register the chest under every type it holds
                    StringBuilder names = new StringBuilder();
                    for (Item item : itemTypes) {
                        if (!names.isEmpty()) names.append(", ");
                        names.append(item.getName().getString());
                        materialDict.put(item, Utils.saveAdd(materialDict.getOrDefault(item, new ArrayList<>()), tempChestPos, mc.player.getEntityPos()));
                    }
                    toolSet.addAll(toolStacks);
                    info("Registered chest §a(" + itemTypes.size() + " types: " + names + ")");
                    state = State.SelectingChests;
                }
            }
            return;
        }

        List<State> allowedStates = Arrays.asList(State.AwaitRestockResponse, State.AwaitMapChestResponse,
            State.AwaitCartographyResponse, State.AwaitFinishedMapChestResponse, State.AwaitFoodChestResponse,
            State.AwaitDepositResponse);
        if (allowedStates.contains(state)) {
            toBeHandledInvPacket = packet;
            timeoutTicks = preRestockDelay.get();
        }
    }

    private void handleInventoryPacket(InventoryS2CPacket packet) {
        if (debugPrints.get()) info("Handling InvPacket for: " + state);
        closeNextInvPacket = true;
        switch (state) {
            case AwaitRestockResponse:
                interactTimeout = 0;
                // Fetching a pickaxe: park any worn (below min-durability) pickaxe into this
                // chest FIRST - one deposit per sync, then the fresh pickaxe is taken below
                if (!restockList.isEmpty() && new ItemStack(restockList.get(0).getLeft()).isIn(ItemTags.PICKAXES)) {
                    for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                        ItemStack invStack = packet.contents().get(slot);
                        if (invStack.isIn(ItemTags.PICKAXES) && ToolUtils.getRemainingDamage(invStack) < minDurability.get()) {
                            mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                            timeoutTicks = invActionDelay.get();
                            return;
                        }
                    }
                }
                boolean foundMaterials = false;
                List<Integer> slots = IntStream.rangeClosed(0, packet.contents().size() - 37)
                    .boxed()
                    .collect(Collectors.toList());
                Collections.shuffle(slots);
                for (int slot : slots) {
                    ItemStack stack = packet.contents().get(slot);

                    if (restockList.get(0).getMiddle() == 0) {
                        foundMaterials = true;
                        break;
                    }
                    // Mixed chests: only take stacks of the material currently being restocked.
                    // Partial stacks are fine - the remaining count is recomputed from the
                    // leftover stack size, so any stack size makes progress.
                    if (!stack.isEmpty() && stack.getItem().equals(restockList.get(0).getLeft())) {
                        //info("Taking Stack of " + restockList.get(0).getLeft().getName().getString());
                        foundMaterials = true;
                        int highestFreeSlot = Utils.findHighestFreeSlot(packet);
                        if (highestFreeSlot == -1) {
                            warning("No free slots found in inventory.");
                            requestDisable();
                            return;
                        }
                        restockBacklogSlots.add(slot);
                        Triple<Item, Integer, Integer> oldTriple = restockList.remove(0);
                        if (stack.isStackable()) {
                            int remainingCount = oldTriple.getRight() - stack.getCount();
                            int remainingStacks = Math.max(0, (int) Math.ceil(remainingCount / 64f));
                            restockList.add(0, Triple.of(oldTriple.getLeft(), remainingStacks, remainingCount));
                        } else {
                            restockList.add(0, Triple.of(oldTriple.getLeft(), oldTriple.getMiddle() - 1, oldTriple.getRight() - 64));
                        }
                    }
                }
                if (!foundMaterials) endRestocking();
                break;
            case AwaitMapChestResponse:
                int mapSlot = -1;
                int paneSlot = -1;
                //Search for map and glass pane
                for (int slot = 0; slot < packet.contents().size() - 36; slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (stack.getItem() == Items.MAP) mapSlot = slot;
                    if (stack.getItem() == Items.GLASS_PANE) paneSlot = slot;
                }
                if (mapSlot == -1 || paneSlot == -1) {
                    warning("Not enough Empty Maps/Glass Panes in Map Material Chest");
                    return;
                }
                interactTimeout = 0;
                timeoutTicks = postRestockDelay.get();
                Utils.getOneItem(mapSlot, false, availableSlots, availableHotBarSlots, packet);
                Utils.getOneItem(paneSlot, true, availableSlots, availableHotBarSlots, packet);
                mc.player.getInventory().setSelectedSlot(availableHotBarSlots.get(0));
                state = State.Walking;
                break;
            case AwaitCartographyResponse:
                interactTimeout = 0;
                timeoutTicks = postRestockDelay.get();
                boolean searchingMap = true;
                for (int slot : availableSlots) {
                    if (slot < 9) {  //Stupid slot correction
                        slot += 30;
                    } else {
                        slot -= 6;
                    }
                    ItemStack stack = packet.contents().get(slot);
                    if (searchingMap && stack.getItem() == Items.FILLED_MAP) {
                        mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        searchingMap = false;
                    }
                }
                for (int slot : availableSlots) {
                    if (slot < 9) {  //Stupid slot correction
                        slot += 30;
                    } else {
                        slot -= 6;
                    }
                    ItemStack stack = packet.contents().get(slot);
                    if (!searchingMap && stack.getItem() == Items.GLASS_PANE) {
                        mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        break;
                    }
                }
                mc.interactionManager.clickSlot(packet.syncId(), 2, 0, SlotActionType.QUICK_MOVE, mc.player);
                checkpoints.add(new Pair(finishedMapChest.getRight(), new Pair("finishedMapChest", null)));
                state = State.Walking;
                break;
            case AwaitFinishedMapChestResponse:
                interactTimeout = 0;
                timeoutTicks = postRestockDelay.get();
                for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (stack.getItem() == Items.FILLED_MAP) {
                        mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        break;
                    }
                }

                // Master joins mining process if a certain amount of lower lines are left to mine
                if (lowerMiningCommand.size() >= masterHelpMinLines.get()) {
                    setInterval(new Pair<>(127, 127));
                    mineLine(-1);
                    return;
                }
                // Else wait for all slaves to finish
                state = State.AwaitNBTFile;
                break;
            case AwaitFoodChestResponse:
                interactTimeout = 0;
                timeoutTicks = postRestockDelay.get();
                // Take one stack of whatever food the chest holds
                for (int slot = 0; slot < packet.contents().size() - 36; slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) {
                        mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        break;
                    }
                }
                state = State.Walking;
                break;
            case AwaitDepositResponse:
                interactTimeout = 0;
                // Queue EVERY carried stack that belongs in this chest (the chest must stock
                // the item); the tick then deposits one per delay tick while the screen stays
                // open - full-inventory syncs only arrive on open, so per-packet re-triggering
                // stalls after the first stack
                depositBacklogSlots.clear();
                for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
                    ArrayList<Pair<BlockPos, Vec3d>> stocking = materialDict.get(stack.getItem());
                    if (stocking == null || !containsChest(stocking, lastInteractedChest)) continue;
                    depositBacklogSlots.add(slot);
                }
                if (depositBacklogSlots.isEmpty()) {
                    // Nothing belongs in THIS chest: hop to the next correct one or continue
                    Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
                    if (nextDepositChest != null) {
                        checkpoints.add(0, new Pair(nextDepositChest.getRight(), new Pair("depositBuilding", nextDepositChest.getLeft())));
                    }
                    timeoutTicks = postRestockDelay.get();
                    state = State.Walking;
                } else {
                    timeoutTicks = invActionDelay.get();
                }
                break;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Deferred disable: calling toggle() from inside onActivate makes Meteor re-enter
        // the activation path recursively (StackOverflowError -> client death)
        // START ALL deferred trigger: fired one tick after the HUD button enabled the
        // module, so onActivate + config load have completed before startBuilding runs
        if (startBuildingRequested) {
            startBuildingRequested = false;
            if (isActive() && !SlaveSystem.isSlave && !SlaveSystem.slaves.isEmpty()) {
                startBuilding();
            }
            return;
        }

        // Supply-run cooldown ticks down; tryPlacingBlock can't re-trigger mid-trip
        if (restockCooldown > 0) restockCooldown--;

        // Placement cooldowns tick down (litematica-style): expired positions become
        // eligible for retry; successfully placed positions are already skipped by the
        // world-state check in getNextBatchPlacementPos
        if (!placementCooldowns.isEmpty()) {
            placementCooldowns.replaceAll((pos, ticks) -> ticks - 1);
            placementCooldowns.values().removeIf(ticks -> ticks <= 0);
        }

        if (pendingDisable) {
            pendingDisable = false;
            if (isActive()) toggle();
            return;
        }

        if (SlaveSystem.isSlave) {
            processPendingStepCommands();
        } else if (stepMasterStage != StepMasterStage.IDLE) {
            tickStepMaster();
            return;
        }

        // Manual break calls remain available for isolated slave diagnostics; the normal run
        // reaches these flags through the step-checker TCP protocol.
        if (state == State.AwaitBreakPermission2 && break2Permitted) {
            break2Permitted = false;
            ensurePickaxeSlot8();
            breakStage = 2;
            state = State.Mining;
        } else if (state == State.AwaitBreakPermissionAll && breakAllPermitted) {
            breakAllPermitted = false;
            startBreakAll();
            return;
        }

        // Inventory filled up mid-break-all: deposit the building blocks, then resume
        // Inventory truly out of space mid-break-all (partial-stack headroom included):
        // deposit the building blocks, then resume
        if (breakStage == 4 && state.equals(State.Mining) && freeInventoryCapacity() == 0) {
            pauseBreakAllForDeposit();
            return;
        }

        // SPATIAL AWARENESS collection during break-all: items dropping from blocks the
        // bot breaks land at its feet — vanilla's ~1-block pickup radius collects them
        // automatically as it walks. No detour checkpoints, no per-item sprint queuing:
        // the bot never leaves its lane. Stragglers that bounce beyond the natural pickup
        // range are swept at the exit verification below (batchExit checks the ground).

        if (state == null) return;

        // Blocks-per-tick pacing: up to N blocks placed per game tick. The old
        // millisecond-delay approach let the bot walk past blocks faster than it
        // could place them (1/tick = 20/s vs ~22/s needed at sprint speed).

        if (!state.equals(debugPreviousState)) {
            debugPreviousState = state;
            if (debugPrints.get()) info("§3[dbg] state → §b" + state
                + "§3 batch=§b" + currentBatch + "§3 stage=§b" + (breakStage == 0 ? "PRINT" : breakStage == 2 ? "SUPPRESS" : breakStage == 4 ? "RECYCLE" : "?")
                + "§3 plan=§b" + (currentBuildPlan == null ? "null" : currentBuildPlan.size()));
        }

        // Checkpoint-level debug: log every checkpoint consumed with its action + position
        if (debugPrints.get() && !checkpoints.isEmpty() && (state.equals(State.Walking) || state.equals(State.Mining))) {
            Pair<Vec3d, Pair<String, BlockPos>> next = checkpoints.getFirst();
            // Log only when the ACTION changes (not every tick of walking toward the same one)
            if (!next.getRight().getLeft().equals(debugLastAction)) {
                debugLastAction = next.getRight().getLeft();
                info(String.format("§3[dbg] checkpoint → §b%s§3 at §b(%.1f, %.1f, %.1f)§3 queue=§b%d",
                    debugLastAction, next.getLeft().x, next.getLeft().y, next.getLeft().z, checkpoints.size()));
            }
        }

        // Slave announces liveness so the master's registered-slave list survives master
        // re-toggles; also reconnects when the master link was lost (e.g. master restart)
        if (SlaveSystem.isSlave) {
            if (++heartbeatTicks >= 40) {
                heartbeatTicks = 0;
                SlaveSystem.sendMessageToMaster("heartbeat");
                if (!LocalTCPTransport.isRunning()) {
                    SlaveSystem.setTcpAddress(tcpIp.get(), tcpPort.get());
                    if (LocalTCPTransport.isRunning()) info("Reconnected to master");
                }
            }
        }

        // Delegate upper/lower mining commands to available slaves
        for (int i = SlaveSystem.slaves.size() - 1; i >= Math.max(0, SlaveSystem.slaves.size()-2); i--) {
            ArrayList<Integer> miningCommand = i % 2 == 1 ? lowerMiningCommand : upperMiningCommand;
            if (!miningCommand.isEmpty()) {
                String slave = SlaveSystem.slaves.get(i);
                if (SlaveSystem.finishedSlavesDict.get(slave)) {
                    int targetLine = miningCommand.removeFirst();
                    SlaveSystem.sendToSlave(slave, "mine:" + targetLine);
                    SlaveSystem.finishedSlavesDict.put(slave, false);
                    SlaveSystem.activeSlavesDict.put(slave, true);
                }
            }
        }

        // Delegate last lower mining commands to master if in master mining mode
        if (masterMiningReady && !lowerMiningCommand.isEmpty()) {
            masterMiningReady = false;
            mineLine(lowerMiningCommand.removeLast());
        }

        if (state.equals(State.AwaitMinedSuppression)) {
            int startZ = Math.max(0, awaitSuppressionZ-15);
            int endZ = Math.min(128, awaitSuppressionZ+15);
            if (SlaveSystem.mapCompletionState.isLineComplete(true, suppressedLines-1, startZ, endZ, false)) {
                state = State.Walking;
            } else {
                return;
            }
        }

        if (interactTimeout > 0) {
            interactTimeout--;
            if (interactTimeout == 0) {
                info("Interaction timed out. Interacting again...");
                if (state == State.AwaitCartographyResponse) {
                    interactWithBlock(cartographyTable.getLeft());
                } else {
                    interactWithBlock(lastInteractedChest);
                }
            }
        }

        if (timeoutTicks > 0) {
            if (mc.player.isOnGround()) timeoutTicks--;
            Utils.setForwardPressed(false);
            return;
        }

        // Swap into Hotbar
        if (toBeSwappedSlot != -1) {
            swapIntoHotbar(toBeSwappedSlot);
            toBeSwappedSlot = -1;
            if (postSwapDelay.get() != 0) {
                timeoutTicks = postSwapDelay.get();
                return;
            }
        }

        // Restocking
        if (restockBacklogSlots.size() > 0) {
            int slot = restockBacklogSlots.remove(0);
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 1, SlotActionType.QUICK_MOVE, mc.player);
            if (restockBacklogSlots.isEmpty()) {
                if (state.equals(State.AwaitRestockResponse)) {
                    endRestocking();
                }
            } else {
                timeoutTicks = invActionDelay.get();
            }
            return;
        }

        // Deposit backlog: one matching stack per delay tick while the chest screen stays
        // open (same mechanism as the restock backlog); when it drains, hop to the next
        // correct chest or continue the queued route. A chest with no room left for the
        // item is marked full and the deposit reroutes to another registered chest.
        if (state == State.AwaitDepositResponse && !depositBacklogSlots.isEmpty()) {
            int slot = depositBacklogSlots.remove(0);
            ItemStack pending = mc.player.currentScreenHandler.slots.get(slot).getStack();
            if (pending.isEmpty()) return;
            if (!chestHasRoomFor(pending)) {
                fullDepositChests.add(lastInteractedChest);
                info("§eChest " + lastInteractedChest.toShortString() + " is full - depositing in another registered chest.");
                Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
                depositBacklogSlots.clear();
                if (nextDepositChest != null) {
                    checkpoints.add(0, new Pair(nextDepositChest.getRight(), new Pair("depositBuilding", nextDepositChest.getLeft())));
                }
                timeoutTicks = postRestockDelay.get();
                state = State.Walking;
                return;
            }
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            if (depositBacklogSlots.isEmpty()) {
                Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
                if (nextDepositChest != null) {
                    checkpoints.add(0, new Pair(nextDepositChest.getRight(), new Pair("depositBuilding", nextDepositChest.getLeft())));
                }
                timeoutTicks = postRestockDelay.get();
                state = State.Walking;
            } else {
                timeoutTicks = invActionDelay.get();
            }
            return;
        }

        // Jobs while mining
        if (state.equals(State.Mining) && !checkpoints.isEmpty()
            && checkpoints.getFirst().getRight().getLeft() == "mineEnd") {

            // Intermediate reporting of upper slave for parallel suppression
            if (SlaveSystem.isSlave && workOnUpper) {
                BlockPos nextPos = getNextMiningPos();
                boolean northToSouth = checkpoints.getFirst().getLeft().getZ() > mc.player.getZ();
                int minedZ;
                if (nextPos == null) {
                    if (northToSouth) minedZ = 128;
                    else minedZ = 0;
                } else {
                    minedZ = nextPos.subtract(getActiveMapCorner()).getZ();
                    int northToSouthModifier = northToSouth ? -1 : 1;
                    minedZ += northToSouthModifier;
                }

                if (minedZ > -1 && minedZ < 129 && Math.abs(minedZ - lastReportedZ) > 0) {
                    lastReportedZ = minedZ;
                    if (northToSouth) {
                        SlaveSystem.sendMessageToMaster("placeStatus:" + workingInterval.getLeft() + ":"
                            + workingInterval.getRight() + ":" + 0 + ":" + minedZ + ":" + false + ":" + true);
                    } else {
                        SlaveSystem.sendMessageToMaster("placeStatus:" + workingInterval.getLeft() + ":"
                            + workingInterval.getRight() + ":" + minedZ + ":" + 128 + ":" + false + ":" + true);
                    }
                }
            }

            // Mined Block Collection
            if (collectBlocks.get()) {
                int zModifier = checkpoints.getFirst().getLeft().getZ() > mc.player.getZ() ? -1 : 1;
                // Master mines right-to-left, slaves left-to-right
                int xModifier = SlaveSystem.isSlave ? 0 : 2;
                for (Entity entity : mc.world.getEntities()) {
                    if ((zModifier == -1 && !collectAllDirections.get()) || isInventoryFull()) break;
                    if (entity instanceof ItemEntity itemEntity
                        && itemEntity.groundCollision
                        && !itemEntity.cannotPickup()
                        && itemEntity.getOwner() == null
                        && itemEntity.getY() >= getActiveMapCorner().getY()
                        && itemEntity.getY() < getActiveMapCorner().getY() + 2
                        && itemEntity.getX() < lowerMapCorner.getX() + workingInterval.getRight() + xModifier + 2
                        && itemEntity.getX() > lowerMapCorner.getX() + workingInterval.getLeft() + xModifier - 3
                        && itemEntity.getZ() <= lowerMapCorner.getZ() + 129
                        && itemEntity.getZ() >= lowerMapCorner.getZ() - 2
                        ) {
                        // Cap the collectionPos to avoid the player trying to walk into walls
                        double clampedX;
                        if (SlaveSystem.isSlave) {
                            clampedX = Math.clamp(itemEntity.getX(), lowerMapCorner.getX() + 0.4f, lowerMapCorner.getX() + workingInterval.getRight() + 0.6f);
                        } else {
                            clampedX = Math.clamp(itemEntity.getX(), lowerMapCorner.getX() + workingInterval.getLeft() + 0.4f, lowerMapCorner.getX() + 127.6f);
                        }
                        Vec3d collectionPos = new Vec3d(
                            clampedX,
                            itemEntity.getY(),
                            Math.min(lowerMapCorner.getZ() + lowerMapLayer[0].length - 0.4f, itemEntity.getZ()));
                        checkpoints.addFirst(new Pair<>(collectionPos, new Pair<>("sprint", null)));
                    }
                }
            }
        }

        if ((state.equals(State.Mining) || state.equals(State.AwaitBlockBreak)) && nextBlockPos != null) {
            // Break block if miningPos is not null
            if (miningBlockState(nextBlockPos).isAir()) {
                nextBlockPos = null;
                state = State.Mining;
                // Full-area sweep: immediately find the next target — no pause.
                // Items drop at the bot's feet and get picked up by vanilla proximity
                // while it keeps breaking. Continuous breaking is faster than pause-and-collect.
                if (fullSweepActive) {
                    checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("fullSweepTick", null)));
                }
            } else {
                // Break up to breaks-per-tick blocks per game tick. During the full-sweep
                // (RESET), currentBuildPlan is null so subsequent targets come from the
                // world scan instead of the plan.
                for (int broken = 0; broken < breaksPerTick.get(); broken++) {
                    BlockPos breakTarget;
                    if (broken == 0) {
                        breakTarget = nextBlockPos;
                    } else if (fullSweepActive) {
                        breakTarget = findNearestPrintBoxBlock();
                    } else {
                        breakTarget = getNextBatchBreakPos();
                    }
                    if (breakTarget == null) break;
                    if (mc.player.getEyePos().distanceTo(breakTarget.toCenterPos()) > interactionRange.get()) break;
                    nextBlockPos = breakTarget;
                    ((com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager) mc.interactionManager).fastBreak(breakTarget); mc.player.swingHand(Hand.MAIN_HAND);
                    if (!miningBlockState(breakTarget).isAir()) break;  // server didn't accept, stop
                }

                if (state.equals(State.AwaitBlockBreak)) {
                    Utils.setForwardPressed(false);
                    return;
                }
            }
        }

        if (state.equals(State.AwaitPlacedSuppression)) {
            if (SlaveSystem.mapCompletionState.isLineComplete(true, suppressedLines+1, true) &&
                SlaveSystem.mapCompletionState.isLineComplete(false, suppressedLines+1, true)) {

                suppressedLines++;
                if (suppressedLines > 0) {
                    // Suppress one line
                    suppressPath();
                } else {
                    // Only continue filling map on first iteration
                    loadFirstLinePath();
                }
            }
        }

        // Load next nbt file
        if (state == State.AwaitNBTFile) {
            if (!SlaveSystem.isSlave) {
                if (!SlaveSystem.allSlavesFinished()) return;
                if (mapFile != null) {
                    try {
                        if (moveToFinishedFolder.get())
                            mapFile.renameTo(new File(mapFile.getParentFile().getAbsolutePath() + File.separator + "_finished_maps" + File.separator + mapFile.getName()));
                    } catch (Exception e) {
                        warning("Failed to move map file " + mapFile.getName() + " to finished map folder");
                        e.printStackTrace();
                    }
                }
            }

            if (!prepareNextMapFile()) {
                return;
            }
            startBuilding();
        }

        // Handle Block Entity interaction response
        if (toBeHandledInvPacket != null) {
            handleInventoryPacket(toBeHandledInvPacket);
            toBeHandledInvPacket = null;
            return;
        }

        if (closeNextInvPacket) {
            if (mc.currentScreen != null) {
                mc.player.closeHandledScreen();
            }
            closeNextInvPacket = false;
        }

        if (!(state.equals(State.Walking) || state.equals(State.Mining))) return;
        if (checkpoints.isEmpty()) return;

        // Main Loop for Building & Mining
        Utils.setForwardPressed(true);
        Vec3d goal = checkpoints.get(0).getLeft();
        if (PlayerUtils.distanceTo(goal.add(0, mc.player.getY() - goal.y, 0)) < checkpointBuffer.get()) {
            Pair<String, BlockPos> checkpointAction = checkpoints.get(0).getRight();
            if (debugPrints.get() && checkpointAction.getLeft() != null)
                info("Reached: §a" + checkpointAction.getLeft());
            if (snapToCheckpoints.get()) mc.player.setPosition(goal.x, mc.player.getY(), goal.z);
            checkpoints.remove(0);

            switch (checkpointAction.getLeft()) {
                case "lineEnd":
                    boolean reachedNorthSide = goal.z < lowerMapCorner.getZ() + lowerMapLayer.length/2;
                    // ToDo: Check last line was completed without missing blocks
                    boolean placing = state.equals(State.Walking) ? true : false;
                    int newPlacedLine = Math.min(workingInterval.getRight()+1, lastPlacedLine + linesPerRun);
                    setLineFinished(lastPlacedLine, newPlacedLine-1, placing, workOnUpper);
                    lastPlacedLine = newPlacedLine;
                    // Remove finished line from workingInterval. Flip snake pattern
                    setInterval(new Pair<>(workingInterval.getLeft() + linesPerRun, workingInterval.getRight()));
                    buildPath(reachedNorthSide, false);
                    break;
                case "mapMaterialChest":
                    state = State.AwaitMapChestResponse;
                    BlockPos mapMaterialChest = getBestChest(Items.CARTOGRAPHY_TABLE).getLeft();
                    interactWithBlock(mapMaterialChest);
                    return;
                case "fillMap":
                    state = State.AwaitPlacedSuppression;
                    Utils.setForwardPressed(false);
                    mc.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, Utils.getNextInteractID(), mc.player.getYaw(), mc.player.getPitch()));
                    return;
                case "awaitSuppression":
                    Utils.setForwardPressed(false);
                    state = State.AwaitPlacedSuppression;
                    if (suppressedLines < 0) return;
                    if (suppressedLines > 0) {
                        if(debugPrints.get()) info("Add lower mining command: " + (suppressedLines-1));
                        lowerMiningCommand.add(suppressedLines-1);
                    }
                    if (suppressedLines < lowerMapLayer.length / 2) {
                        if(debugPrints.get()) info("Add upper mining command: " + (suppressedLines));
                        upperMiningCommand.add(suppressedLines);
                    }
                    return;
                case "startSneak":
                    Utils.setSneakPressed(true);
                    break;
                case "stopSneak":
                    Utils.setForwardPressed(false);
                    Utils.setSneakPressed(false);
                    timeoutTicks = mapLoadTimeout.get();
                    awaitSuppressionZ =  (int) Math.floor(goal.z) - lowerMapCorner.getZ();
                    int startZ = Math.max(0, awaitSuppressionZ-15);
                    int endZ = Math.min(128, awaitSuppressionZ+15);
                    if (SlaveSystem.mapCompletionState.isLineComplete(true, suppressedLines-1, startZ, endZ, false)) {
                        return;
                    }
                    state = State.AwaitMinedSuppression;
                    return;
                case "swapMap":
                    // Avoid destroying the map by loading it
                    FindItemResult mapItem = InvUtils.findInHotbar(Items.FILLED_MAP);
                    if (!mapItem.found()) break;
                    // If there is a non-hotbar-slot. Swap the map to it
                    if (availableSlots.size() > availableHotBarSlots.size()) {
                        for (int slot : availableSlots) {
                            if (availableHotBarSlots.contains(slot)) continue;
                            Utils.performSwap(slot, mapItem.slot());
                        }
                    } else {
                        // Switch to something that is not the filled map
                        int targetSlot = mapItem.found() ? (mapItem.slot()+1) % 9 : 8;
                        mc.player.getInventory().setSelectedSlot(targetSlot);
                    }
                    break;
                case "cartographyTable":
                    state = State.AwaitCartographyResponse;
                    Utils.setForwardPressed(false);
                    interactWithBlock(cartographyTable.getLeft());
                    return;
                case "finishedMapChest":
                    state = State.AwaitFinishedMapChestResponse;
                    interactWithBlock(finishedMapChest.getLeft());
                    return;
                case "calculateMiningRefill":
                    refillMiningInventory(true);
                    return;
                case "calculateRefill":
                    HashMap<Item, Integer> requiredItems = getRequiredItems();
                    Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInformation = Utils.getInvInformation(requiredItems, availableSlots);
                    refillBuildingInventory(invInformation.getRight());
                    return;
                case "refill":
                    state = State.AwaitRestockResponse;
                    Utils.setForwardPressed(false);
                    interactWithBlock(checkpointAction.getRight());
                    return;
                case "mineBegin":
                    lastReportedZ = -1;
                    break;
                case "mineEnd":
                    finishMineLine();
                    return;
                case "mineFinished":
                    finishMineLine();
                    return;
                case "slaveFinished":
                    setMiningFinished();
                    return;
                case "foodChest":
                    state = State.AwaitFoodChestResponse;
                    Utils.setForwardPressed(false);
                    interactWithBlock(checkpointAction.getRight());
                    return;
                case "swapOffhand":
                    setupHands();
                    break;
                case "batchBegin":
                    // Simple rule: no usable pickaxe in reserved slot 8/inventory -> walk to
                    // the restock station, get one, then continue. No failure path.
                    if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                        movePickaxeToSlot8();
                        if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                            restockPickaxeTrip(goal);
                            return;
                        }
                    }
                    break;
                case "printDone":
                    verifyPrintPass();
                    break;
                case "printDone2":
                    if (!verifyPrintPass2()) return;
                    break;
                case "stepPrimingReady":
                    if (!verifyCurrentPlan("Priming")) return;
                    info("§aPriming placement verified. Waiting for the master's recessive walk.");
                    SlaveSystem.sendMessageToMaster("step:ready:prime");
                    state = State.Walking;
                    break;
                case "stepPrimingWait":
                    state = State.StandBy;
                    Utils.setForwardPressed(false);
                    return;
                case "awaitBreak2":
                    if (printRepairPending) break;   // repair pass: walk through lane -1 placing
                    info("§aBatch " + currentBatch + " placed. Waiting for master's break(2) permission.");
                    breakStage = 0;
                    state = State.AwaitBreakPermission2;
                    Utils.setForwardPressed(false);
                    SlaveSystem.sendMessageToMaster("step:ready:" + currentBatch);
                    return;
                case "break2Done":
                    if (printRepairPending) {
                        // Repair pass 2: the center-lane blocks are already placed from the
                        // first print pass — the bot can't walk the center. It moves to
                        // LANE 2's north staging and walks south placing still-missing
                        // blocks from there, then proceeds to the normal break(2) wait.
                        printRepairPending = false;
                        double repairX = lowerMapCorner.getX() + 2 * currentBatch + 2 + 0.5;  // lane 2 block center
                        double laneMinus1X = lowerMapCorner.getX() + 2.0 * currentBatch + 1.5;
                        double northZ = lowerMapCorner.getZ() - 2;
                        double southZ = lowerMapCorner.getZ() + 128.7;
                        checkpoints.add(0, new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
                        checkpoints.add(0, new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
                        checkpoints.add(0, new Pair(new Vec3d(repairX, lowerMapCorner.getY(), southZ), new Pair("printDone2", null)));
                        checkpoints.add(0, new Pair(new Vec3d(repairX, lowerMapCorner.getY(), northZ), new Pair("sprint", null)));
                        state = State.Walking;
                        return;
                    }
                    if (!verifyBreak2()) return;
                    state = State.Walking;
                    break;
                case "awaitBreakAll":
                    info("§aBatch " + currentBatch + " break(2) complete. Waiting for master's break-all permission.");
                    state = State.AwaitBreakPermissionAll;
                    Utils.setForwardPressed(false);
                    SlaveSystem.sendMessageToMaster("step:break2done:" + currentBatch);
                    return;
                case "break2Resume":
                    ensurePickaxeSlot8();
                    breakStage = 2;
                    state = State.Mining;
                    break;
                case "breakAllBegin":
                    ensurePickaxeSlot8();
                    breakStage = 4;
                    state = State.Mining;
                    break;
                case "breakAllDone":
                    break;
                case "stepClosingReady":
                    if (!verifyCurrentPlan("Closing")) return;
                    info("§aClosing placement verified. Waiting for the master's final walk.");
                    SlaveSystem.sendMessageToMaster("step:ready:close");
                    state = State.Walking;
                    break;
                case "stepClosingWait":
                    state = State.AwaitClosingBreak;
                    Utils.setForwardPressed(false);
                    return;
                case "stepClosingDone":
                    finishClosing();
                    return;
                case "sweepTick": {
                    // COMBINED spatial-awareness SWEEP: nearest actionable target (block or
                    // item), using the shared findNearestGroundItem helper (no duplicates)
                    sweepBudget--;
                    BlockPos nearestBlock = null;
                    double blockDist = Double.MAX_VALUE;
                    if (currentBuildPlan != null) {
                        for (BlockPos pos : currentBuildPlan.keySet()) {
                            if (miningBlockState(pos).isAir()) continue;
                            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
                            if (dist < blockDist) { blockDist = dist; nearestBlock = pos; }
                        }
                    }
                    ItemEntity nearestItem = freeInventoryCapacity() > 0 ? findNearestGroundItem(3) : null;
                    double itemDist = nearestItem != null
                        ? mc.player.getEyePos().distanceTo(nearestItem.getEntityPos()) : Double.MAX_VALUE;
                    if (sweepBudget > 0 && freeInventoryCapacity() > 0) {
                        if (nearestBlock != null && (nearestItem == null || blockDist <= itemDist)) {
                            checkpoints.addFirst(new Pair(nearestBlock.toCenterPos(), new Pair("sweepTick", null)));
                            breakStage = 4;
                            state = State.Mining;
                            return;
                        } else if (nearestItem != null) {
                            targetGroundItem(nearestItem, "sweepTick");
                            return;
                        }
                    }
                    collectingStragglers = false;
                    checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
                    state = State.Walking;
                    return;
                }
                case "fullSweepStart":
                case "fullSweepCenter":
                    // Arrived at a vantage point: begin tick-based spatial sweep of the
                    // entire print box (nearest non-air block, walk + break, re-evaluate)
                    fullSweepActive = true;
                    fullSweepBudget = 600;
                    checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("fullSweepTick", null)));
                    state = State.Walking;
                    info("§7Full-area sweep: scanning print box from vantage point.");
                    return;
                case "fullSweepTick": {
                    // Spatial awareness full-area sweep: find the nearest actionable target
                    // (block to break OR ground item to collect), walk to it, handle it.
                    // Uses the SAME shared helpers as the batch SWEEP (findNearestGroundItem,
                    // targetGroundItem) — no duplicated item-scan code.
                    // Vantage progression: north-center → map-center → complete only when
                    // both confirm empty (unloaded chunks read as air).
                    fullSweepBudget--;
                    BlockPos target = findNearestPrintBoxBlock();
                    ItemEntity nearestItem = freeInventoryCapacity() > 0 ? findNearestGroundItem(3) : null;
                    double itemDist = nearestItem != null
                        ? mc.player.getEyePos().distanceTo(nearestItem.getEntityPos()) : Double.MAX_VALUE;

                    if (target == null && nearestItem == null || fullSweepBudget <= 0) {
                        // Nothing visible from HERE — but blocks may exist beyond render
                        // distance. Progress to the center vantage before declaring complete.
                        if (!fullSweepCenterChecked && fullSweepBudget > 0) {
                            fullSweepCenterChecked = true;
                            double centerX = lowerMapCorner.getX() + 64.0;
                            double mapCenterZ = lowerMapCorner.getZ() + 64.0;
                            checkpoints.clear();
                            checkpoints.add(new Pair(new Vec3d(centerX, lowerMapCorner.getY(), mapCenterZ), new Pair("fullSweepCenter", null)));
                            state = State.Walking;
                            info("§7North vantage clean — moving to map-center to scan the south half.");
                            return;
                        }
                        // Both vantage points confirmed clean (or budget expired)
                        fullSweepActive = false;
                        checkpoints.clear();
                        checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("fullSweepDone", null)));
                        Pair<BlockPos, Vec3d> depositChest = getBestDepositChest();
                        if (depositChest != null) {
                            checkpoints.addFirst(new Pair(depositChest.getRight(), new Pair("depositBuilding", depositChest.getLeft())));
                        }
                        state = State.Walking;
                        info("§aFull-area sweep complete - depositing and returning to standby.");
                        return;
                    }

                    // Target whichever is closer: a block to break or an item to collect
                    double blockDist = target != null ? mc.player.getEyePos().distanceTo(target.toCenterPos()) : Double.MAX_VALUE;
                    if (target != null && blockDist <= itemDist) {
                        nextBlockPos = target;
                        breakStage = 4;
                        state = State.Mining;
                        Vec3d targetCenter = target.toCenterPos();
                        Vec3d botPos = mc.player.getEntityPos();
                        double dx = botPos.x - targetCenter.x;
                        double dz = botPos.z - targetCenter.z;
                        double len = Math.sqrt(dx * dx + dz * dz);
                        if (len > 0.5) {
                            Vec3d walkTo = new Vec3d(
                                targetCenter.x + (dx / len) * 1.5,
                                botPos.y,
                                targetCenter.z + (dz / len) * 1.5);
                            checkpoints.clear();
                            checkpoints.add(new Pair(walkTo, new Pair("fullSweepTick", null)));
                        }
                        if (debugPrints.get()) {
                            info(String.format("§3[dbg] fullSweep block §b%s§3 dist=§b%.1f", target.toShortString(), blockDist));
                        }
                    } else if (nearestItem != null) {
                        targetGroundItem(nearestItem, "fullSweepTick");
                        if (debugPrints.get()) {
                            info(String.format("§3[dbg] fullSweep item dist=§b%.1f", itemDist));
                        }
                    }
                    return;
                }
                case "fullSweepDone":
                    fullSweepActive = false;
                    state = State.StandBy;
                    Utils.setForwardPressed(false);
                    SlaveSystem.sendMessageToMaster("step:done:" + Math.max(currentBatch, 0));
                    info("§aRESET complete - print area cleared, items deposited, standby.");
                    return;
                case "depositBuilding":
                    state = State.AwaitDepositResponse;
                    Utils.setForwardPressed(false);
                    interactWithBlock(checkpointAction.getRight());
                    return;
            }
            if (checkpoints.isEmpty()) {
                if (state.equals(State.Walking)) {
                    // Done Building
                    info("Done placing map");
                    if (SlaveSystem.isSlave) {
                        checkpoints.add(new Pair(mc.player.getEntityPos(), new Pair("slaveFinished", null)));
                    } else {
                        // Master
                        fillMapPath();
                    }
                }
            }
            goal = checkpoints.get(0).getLeft();
        }

        // Set yaw rotation to goal
        double lookZ = goal.z;
        if (PlayerUtils.distanceTo(goal) > 2) {
            lookZ = mc.player.getZ() + Math.max(Math.min(goal.z - mc.player.getZ(), 1), -1);
        }
        mc.player.setYaw((float) Rotations.getYaw(new Vec3d(goal.x, goal.y, lookZ)));

        String nextAction = checkpoints.get(0).getRight().getLeft();

        // Set sprint mode
        final List<String> noSprintActions = Arrays.asList("lineBegin", "lineEnd", "mineBegin", "mineEnd");
        if (noSprintActions.contains(nextAction) && sprinting.get() != SprintMode.Always) {
            mc.player.setSprinting(false);
        } else if (sprinting.get() != SprintMode.Off) {
            mc.player.setSprinting(true);
        }
        final List<String> allowPlaceActions = Arrays.asList("lineBegin", "lineEnd", "sprint", "mineBegin", "mineEnd",
            "batchBegin", "printDone", "printDone2", "stepPrimingReady", "stepPrimingWait", "awaitBreak2",
            "break2Done", "breakAllBegin", "breakAllDone", "stepClosingReady", "stepClosingWait", "stepClosingDone",
            "batchExit", "sweepTick", "fullSweepStart", "fullSweepCenter", "fullSweepTick", "fullSweepDone");
        if (!allowPlaceActions.contains(nextAction)) return;

        BlockPos tempPos;
        if (state.equals(State.Walking)) {
            tempPos = (currentBuildPlan != null && breakStage == 0) ? getNextBatchPlacementPos() : getNextPlacementPos(goal.z >= mc.player.getZ());
        } else {
            tempPos = fullSweepActive ? nextBlockPos
                : breakStage != 0 ? getNextBatchBreakPos() : getNextMiningPos();
        }

        // ToDo fix this
        if (tempPos == null || PlayerUtils.distanceTo(tempPos.toCenterPos()) > interactionRange.get()) return;
        nextBlockPos = tempPos;

        if (state.equals(State.Walking)) {
            if (breakStage == 0 && currentBuildPlan != null) {
                // Place up to blocks-per-tick blocks per game tick. Each iteration
                // re-targets the nearest eligible block behind the bot. Adds a
                // cooldown to each attempted position (litematica-style) so failed
                // placements don't get hammered repeatedly in the same tick window.
                for (int placed = 0; placed < blocksPerTick.get(); placed++) {
                    BlockPos target = getNextBatchPlacementPos();
                    if (target == null) break;
                    nextBlockPos = target;
                    placementCooldowns.put(target, 4);  // ~0.2s before retry
                    tryPlacingBlock();
                    // Stop if the block didn't land (swap/restock path triggered)
                    if (!miningBlockState(target).isAir()) continue;  // placed, next
                    break;  // not placed — stop and let the tick handle the swap/restock
                }
            } else if (breakStage == 0) {
                tryPlacingBlock();  // legacy path (no batch plan)
            }
        } else {
            if (breakStage != 0) {
                // Batch breaking always swings the reserved pickaxe slot (hotbar slot 8)
                ItemStack reserved = mc.player.getInventory().getStack(8);
                if (!reserved.isIn(ItemTags.PICKAXES) || ToolUtils.getRemainingDamage(reserved) < minDurability.get()) {
                    if (!hasGoodPickaxe()) {
                        // Pickaxe broke or went missing mid-break: supply run via the north checkpoint
                        pauseForRestockTrip(true);
                    } else {
                        movePickaxeToSlot8();
                    }
                    return;
                }
                mc.player.getInventory().setSelectedSlot(8);
                // Break up to breaks-per-tick blocks per game tick (same loop pattern
                // as the placement: re-target the nearest eligible block each iteration)
                for (int broken = 0; broken < breaksPerTick.get(); broken++) {
                    BlockPos breakTarget = broken == 0 ? nextBlockPos : getNextBatchBreakPos();
                    if (breakTarget == null) break;
                    if (mc.player.getEyePos().distanceTo(breakTarget.toCenterPos()) > interactionRange.get()) break;
                    nextBlockPos = breakTarget;
                    ((com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager) mc.interactionManager).fastBreak(breakTarget); mc.player.swingHand(Hand.MAIN_HAND);
                    if (!miningBlockState(breakTarget).isAir()) continue;  // not yet broken, stop
                }
                return;
            }
            BlockState blockState = MapAreaCache.getCachedBlockState(nextBlockPos);
            ItemStack bestTool = ToolUtils.getBestTool(toolSet, blockState);
            int bestHotbarSlot = -1;
            // Search for the tool with the optimal tool type and minimal durability above the threshold
            for (int slot : availableHotBarSlots) {
                if (mc.player.getInventory().getStack(slot).isEmpty()) continue;
                ItemStack itemStack = mc.player.getInventory().getStack(slot);
                if (itemStack.getItem().equals(bestTool.getItem())) {
                    int remainingDamage = ToolUtils.getRemainingDamage(itemStack);
                    if (remainingDamage < minDurability.get()) continue;
                    if (bestHotbarSlot == -1 || remainingDamage < ToolUtils.getRemainingDamage(bestTool)) {
                        bestHotbarSlot = slot;
                        bestTool = itemStack;
                    }
                }
            }
            if (bestHotbarSlot == -1) {
                warning("No tool with enough durability found to mine blocks");
                return;
            }
            InvUtils.swap(bestHotbarSlot, false);
            ((com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager) mc.interactionManager).fastBreak(nextBlockPos); mc.player.swingHand(Hand.MAIN_HAND);
            state = State.Mining;
        }
    }

    // Restocking

    private Pair<BlockPos, Vec3d> getBestChest(Item item) {
        ArrayList<Pair<BlockPos, Vec3d>> list = new ArrayList<>();
        if (item.equals(Items.CARTOGRAPHY_TABLE)) {
            list = mapMaterialChests;
        } else if (materialDict.containsKey(item)) {
            list = materialDict.get(item);
        } else {
            warning("No chest found for " + item.getName().getString());
            toggle();
            return new Pair<>(new BlockPos(0, 0, 0), new Vec3d(0, 0, 0));
        }
        ArrayList<Pair<BlockPos, Vec3d>> bestList = new ArrayList<>();
        // Get lowest/heighest chest (depending on layer) as 1. criteria
        for (Pair<BlockPos, Vec3d> p : list) {
            //Skip chests that have already been checked
            if (checkedChests.contains(p.getLeft())) continue;
            if (bestList.isEmpty() || ((bestList.getFirst().getLeft().getY() <= p.getLeft().getY()) && workOnUpper)
                || ((bestList.getFirst().getLeft().getY() >= p.getLeft().getY()) && !workOnUpper)) {
                // Remove all previous results if new one is truly better
                if (!bestList.isEmpty() && bestList.getFirst().getLeft().getY() != p.getLeft().getY()) {
                    bestList.clear();
                }
                bestList.add(p);
            }
        }
        if (bestList.isEmpty()) {
            if (checkedChests.isEmpty()) {
                // The source list itself has no usable chest - retrying cannot help (was: infinite recursion)
                warning("No available chest found for " + item.getName().getString());
                toggle();
                return new Pair<>(new BlockPos(0, 0, 0), new Vec3d(0, 0, 0));
            }
            checkedChests.clear();
            return getBestChest(item);
        }
        // Get nearest chest as 2. criteria
        Vec3d bestPos = null;
        BlockPos bestChestPos = null;
        for (Pair<BlockPos, Vec3d> p : bestList) {
            //Skip chests that have already been checked
            if (checkedChests.contains(p.getLeft())) continue;
            if (bestPos == null || PlayerUtils.distanceTo(p.getRight()) < PlayerUtils.distanceTo(bestPos)) {
                bestPos = p.getRight();
                bestChestPos = p.getLeft();
            }
        }
        return new Pair(bestChestPos, bestPos);
    }

    private void refillBuildingInventory(HashMap<Item, Integer> invMaterial) {
        //Fills restockList with required build materials
        restockList.clear();
        HashMap<Item, Integer> requiredItems = getRequiredItems();
        for (Item item : invMaterial.keySet()) {
            int oldAmount = requiredItems.remove(item);
            requiredItems.put(item, oldAmount - invMaterial.get(item));
        }

        for (Item item : requiredItems.keySet()) {
            if (requiredItems.get(item) <= 0) continue;
            int stacks = (int) Math.ceil((float) requiredItems.get(item) / 64f);
            if(debugPrints.get()) info("Restocking §a" + stacks + " stacks " + item.getName().getString() + " (" + requiredItems.get(item) + ")");
            restockList.add(0, Triple.of(item, stacks, requiredItems.get(item)));
        }
        addClosestRestockCheckpoint();
    }

    private void refillMiningInventory(boolean includeFutureBlocks) {
        // Fills restockList mining tools for mining current/future blocks
        restockList.clear();
        // Calculate total uses per tool
        HashMap<ItemStack, Integer> toolUseDict = new HashMap<>();
        forEachMapBlock((x, z, y, blockState, block) -> {
            BlockState cachedBlockState = MapAreaCache.getCachedBlockState(getActiveMapCorner().add(x,y,z));
            Block cachedBlock = cachedBlockState == null ? null : cachedBlockState.getBlock();
            Block miningBlock = includeFutureBlocks ? getActiveMapLayer()[x][z][y] : cachedBlock;
            if (miningBlock == null) return true;
            BlockState miningBlockState = miningBlock.getDefaultState();
            ItemStack bestTool = ToolUtils.getBestTool(toolSet, miningBlockState);
            if (bestTool == null || (miningBlockState.getHardness(mc.world, getActiveMapCorner().add(x,y,z)) == 0.0f)) return true;
            if (toolUseDict.containsKey(bestTool)) {
                toolUseDict.put(bestTool, toolUseDict.get(bestTool) + 1);
            } else {
                toolUseDict.put(bestTool, 1);
            }
            return true;
        }, true);

        for (ItemStack itemStack : toolUseDict.keySet()) {
            // Fetch unbreaking enchantment level
            RegistryEntry<Enchantment> unbreaking = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .getOrThrow(Enchantments.UNBREAKING);
            int unbreakingLevel = itemStack.getEnchantments().getLevel(unbreaking);

            int rawUses = toolUseDict.get(itemStack);
            double adjustedUses = (float) rawUses / (float) (unbreakingLevel + 1) * (1 + durabilityBuffer.get());
            for (int slot : availableSlots) {
                ItemStack invTool = mc.player.getInventory().getStack(slot);
                if (invTool.getItem() == itemStack.getItem()) {
                    adjustedUses = Math.max(0, adjustedUses - (ToolUtils.getRemainingDamage(invTool) - minDurability.get()));
                }
            }

            int itemsNeeded = (int) Math.ceil((float) adjustedUses / (float) itemStack.getMaxDamage());
            if (itemsNeeded == 0) continue;
            if (debugPrints.get()) info("Restocking §a" + itemsNeeded + " " + itemStack.getItem().getName().getString() + " (" + rawUses + " uses)");
            restockList.add(0, Triple.of(itemStack.getItem().asItem(), itemsNeeded, itemsNeeded));
        }

        float goalX = 0.5f;
        if (!SlaveSystem.isSlave) goalX = 126.5f;
        checkpoints.add(new Pair<>(getActiveMapCorner().toCenterPos().add(goalX,-0.5f, -1), new Pair<>("slaveFinished", null)));

        addClosestRestockCheckpoint();
    }

    private void addClosestRestockCheckpoint() {
        //Determine closest restock chest for material in restock list
        if (restockList.isEmpty()) return;
        double smallestDistance = Double.MAX_VALUE;
        Triple<Item, Integer, Integer> closestEntry = null;
        Pair<BlockPos, Vec3d> restockPos = null;
        for (Triple<Item, Integer, Integer> entry : restockList) {
            Pair<BlockPos, Vec3d> bestRestockPos = getBestChest(entry.getLeft());
            if (bestRestockPos.getLeft() == null) {
                warning("No chest found for " + entry.getLeft().getName().getString());
                toggle();
                return;
            }
            double chestDistance = PlayerUtils.distanceTo(bestRestockPos.getRight());
            if (chestDistance < smallestDistance) {
                smallestDistance = chestDistance;
                closestEntry = entry;
                restockPos = bestRestockPos;
            }
        }
        //Set closest material as first and as checkpoint
        restockList.remove(closestEntry);
        restockList.addFirst(closestEntry);
        checkpoints.addFirst(new Pair(restockPos.getRight(), new Pair("refill", restockPos.getLeft())));
    }

    private void endRestocking() {
        if (restockList.get(0).getMiddle() > 0) {
            checkedChests.add(lastInteractedChest);
            // Stop the re-search cycle once every chest stocking this item has been tried
            List<Pair<BlockPos, Vec3d>> stockingChests =
                materialDict.getOrDefault(restockList.get(0).getLeft(), new ArrayList<>());
            int untriedChests = 0;
            for (Pair<BlockPos, Vec3d> p : stockingChests) {
                if (!checkedChests.contains(p.getLeft())) untriedChests++;
            }
            if (untriedChests == 0) {
                error("No registered chest has enough " + restockList.get(0).getLeft().getName().getString()
                    + " (need " + restockList.get(0).getMiddle() + " more). Restock the chests and restart the module.");
                toggle();
                return;
            }
            warning("Not all necessary stacks restocked. Searching for another chest stocking "
                + restockList.get(0).getLeft().getName().getString() + "...");
            //Search for the next best chest that still stocks the item currently being restocked
            Pair<BlockPos, Vec3d> bestRestockPos = getBestChest(restockList.get(0).getLeft());
            checkpoints.addFirst(new Pair<>(bestRestockPos.getRight(), new Pair<>("refill", bestRestockPos.getLeft())));
        } else {
            checkedChests.clear();
            restockList.remove(0);
            addClosestRestockCheckpoint();
        }
        timeoutTicks = postRestockDelay.get();
        state = State.Walking;
    }

    // Block Interactions

    private void interactWithBlock(BlockPos chestPos) {
        Utils.setForwardPressed(false);
        mc.player.setYaw((float) Rotations.getYaw(chestPos.toCenterPos()));
        mc.player.setPitch((float) Rotations.getPitch(chestPos.toCenterPos()));

        BlockHitResult hitResult = new BlockHitResult(chestPos.toCenterPos(), Utils.getInteractionSide(chestPos), chestPos, false);
        BlockUtils.interact(hitResult, Hand.MAIN_HAND, true);

        //Set timeout for chest interaction
        interactTimeout = retryInteractTimer.get();
        lastInteractedChest = chestPos;
    }

    private void tryPlacingBlock() {
        Item material;
        if (currentBuildPlan != null) {
            Block planned = currentBuildPlan.get(nextBlockPos);
            if (planned == null) return;
            material = planned.asItem();
            // PLACEMENT DIAGNOSTIC: log every batch placement with its z relative to the bot
            // — positive = AHEAD (south), negative = BEHIND (north). If any positive value
            // appears, the behind-filter is broken.
            if (debugPrints.get()) {
                double dz = nextBlockPos.getZ() - mc.player.getZ();
                info(String.format("§3[dbg] PLACE %s at z=%d (bot z=%.1f, dz=%+.1f %s)",
                    material.getName().getString(), nextBlockPos.getZ(), mc.player.getZ(), dz,
                    dz > 0 ? "§cAHEAD!" : "§abehind"));
            }
        } else {
            BlockPos relativePos = nextBlockPos.subtract(getActiveMapCorner());
            material = getActiveMapLayer()[relativePos.getX()][relativePos.getZ()][relativePos.getY()].asItem();
        }
        //info("Placing " + material.getName().getString() + " at: " + relativePos.toShortString());
        //Check hot-bar slots
        for (int slot : availableHotBarSlots) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) continue;
            Item foundMaterial = mc.player.getInventory().getStack(slot).getItem();
            if (foundMaterial.equals(material)) {
                // Direct placement via interaction manager (litematica-style air-place):
                // construct a BlockHitResult on the target position itself (inside-block
                // hit = true). This works through walls because we don't rely on a visible
                // neighbor face — soft anticheats (6b6t) accept this without rotation.
                mc.player.getInventory().setSelectedSlot(slot);
                BlockHitResult hitResult = new BlockHitResult(
                    nextBlockPos.toCenterPos(), Direction.UP, nextBlockPos, true);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                mc.player.swingHand(Hand.MAIN_HAND);
                if (material == lastSwappedMaterial) lastSwappedMaterial = null;
                return;
            }
        }
        for (int slot : availableSlots) {
            if (mc.player.getInventory().getStack(slot).isEmpty() || availableHotBarSlots.contains(slot)) continue;
            Item foundMaterial = mc.player.getInventory().getStack(slot).getItem();
            if (foundMaterial.equals(material)) {
                lastSwappedMaterial = material;
                toBeSwappedSlot = slot;
                Utils.setForwardPressed(false);
                timeoutTicks = preSwapDelay.get();
                return;
            }
        }
        if (lastSwappedMaterial == material) return;      //Wait for swapped material
        if (currentBuildPlan != null) {
            // Mid-print supply run: refill the missing material via the north checkpoint
            pauseForRestockTrip(false);
            return;
        }
        if (debugPrints.get()) info("No " + material.getName().getString() + " found in inventory. Resetting...");
        Vec3d pathCheckpoint = new Vec3d(mc.player.getX(), lowerMapCorner.getY(), lowerMapCorner.north().toCenterPos().getZ());

        if (!checkpoints.isEmpty() && checkpoints.getFirst().getLeft().z < mc.player.getZ()
            && mc.player.getZ() < lowerMapCorner.getZ() + lowerMapLayer.length/2) {
            // If player walks north and has built more than 50% continue placing south-to-north
            checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("walkRestock", null)));
            checkpoints.addFirst(new Pair(pathCheckpoint, new Pair("walkRestock", null)));
        } else {
            // Else just start the line again north-to-south
            checkpoints.clear();
            buildPath(true, true);
        }
        checkpoints.addFirst(new Pair(pathCheckpoint, new Pair("calculateRefill", null)));
    }

    private BlockPos getNextPlacementPos(boolean movingSouth) {
        // Get next block in working interval to place
        for (int x = workingInterval.getLeft(); x <= workingInterval.getRight(); x += linesPerRun) {
            for (int z = 0; z < lowerMapLayer[0].length; z++) {
                for (int lineBonus = 0; lineBonus < linesPerRun; lineBonus++) {
                    int adjustedX = x + lineBonus;
                    if (!Utils.isInInterval(workingInterval, adjustedX)) break;
                    for (int y = 0; y < lowerMapLayer[0][0].length; y++) {
                        int adjustedZ = !movingSouth ? lowerMapLayer[0].length-1-z : z;
                        BlockPos blockPos = getActiveMapCorner().add(adjustedX, y, adjustedZ);
                        BlockState blockState = MapAreaCache.getCachedBlockState(blockPos);
                        // Check if block is not placed already, should not be placed at all, or is too far east
                        // On the last line, ignore the x check as the player is walking on the line
                        if (blockState.isAir() && getActiveMapLayer()[adjustedX][adjustedZ][y] != null
                            && (mc.player.getX() > blockPos.toCenterPos().getX() + 0.5f
                            || (mc.player.getBlockX() - getActiveMapCorner().getX() >= workingInterval.getRight()
                            && !movingSouth &&  mc.player.getZ() + 0.3f < blockPos.toCenterPos().getZ()))) {
                            return blockPos;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Authoritative block state for MINING decisions - the live world, not the map cache.
     * The cache can go stale-air over a region (then nothing targets, nothing breaks, and the
     * bot silently walks into the un-mined wall); the world is always the truth for breaking.
     */
    private BlockState miningBlockState(BlockPos pos) {
        if (mc.world != null) return mc.world.getBlockState(pos);
        return MapAreaCache.getCachedBlockState(pos);
    }

    private BlockPos getNextMiningPos() {
        // Get next block in working interval to mine
        boolean flippedX = !SlaveSystem.isSlave;
        boolean flippedZ = false;
        for (int x = workingInterval.getLeft(); x <= workingInterval.getRight(); x += 2) {
            int reversedX = flippedX ? lowerMapLayer.length - 2 - x + workingInterval.getLeft() : x;
            for (int z = 0; z < lowerMapLayer[0].length; z++) {
                for (int lineBonus = 0; lineBonus < 2; lineBonus++) {
                    int adjustedX = reversedX + lineBonus;
                    if (!Utils.isInInterval(workingInterval, adjustedX)) break;
                    for (int y = lowerMapLayer[0][0].length-1; y >= 0; y--) {
                        int adjustedZ = flippedZ ? lowerMapLayer[0].length-1-z : z;
                        BlockPos blockPos = getActiveMapCorner().add(adjustedX, y, adjustedZ);
                        BlockState blockState = miningBlockState(blockPos);
                        if (!blockState.isAir()) {
                            return blockPos;
                        }
                    }
                }
            }
            flippedZ = !flippedZ;
        }
        return null;
    }

    // Path and Building Management

    private void buildPath(boolean startNorthSide, boolean sprintFirst) {
        // info("buildpath | northside: " + startNorthSide + " | sprint: " + sprintFirst);
        // Iterate over map and skip completed lines. Player has to be able to see the complete map area
        // Fills checkpoints list
        boolean northToSouth = startNorthSide;
        boolean isLastLine = false;
        checkpoints.clear();
        for (int x = workingInterval.getLeft(); x <= workingInterval.getRight(); x += linesPerRun) {
            // Last line hast to be south-to-north to not get stuck
            if (!Utils.isInInterval(workingInterval, x+linesPerRun) && northToSouth) {
                northToSouth = false;
                isLastLine= true;
            }

            boolean lineFinished = true;
            int lastPlacedZ = 128;
            for (int lineBonus = 0; lineBonus < linesPerRun; lineBonus++) {
                int adjustedX = x + lineBonus;
                if (!Utils.isInInterval(workingInterval, adjustedX)) break;
                for (int z = 0; z < upperMapLayer[0].length; z++) {
                    int adjustedZ = northToSouth ? z : lowerMapLayer[0].length - z - 1;
                    for (int y = 0; y < upperMapLayer[0][0].length; y++) {
                        BlockState blockState = MapAreaCache.getCachedBlockState(getActiveMapCorner().add(adjustedX, y, adjustedZ));
                        if (blockState.isAir() && getActiveMapLayer()[adjustedX][adjustedZ][y] != null) {
                            //If there is a replaceable block and not an ignored block type at the position. Mark the line as not done
                            lineFinished = false;
                        }
                        if (!blockState.isAir() && adjustedX == workingInterval.getRight()) lastPlacedZ = adjustedZ-2;
                    }
                }
            }
            if (lineFinished) {
                setLineFinished(x, x + linesPerRun - 1, true, workOnUpper);
                continue;
            }
            float cpX = Math.min(workingInterval.getRight() , x + linesPerRun);
            float cp2Z = isLastLine ? lastPlacedZ + 0.3f : 128 + 0.2f;
            Vec3d cp1 = getActiveMapCorner().toCenterPos().add(cpX, -0.5f, -2);
            Vec3d cp2 = getActiveMapCorner().toCenterPos().add(cpX, -0.5f, cp2Z);

            if (northToSouth) {
                checkpoints.add(new Pair(cp1, new Pair("lineBegin", null)));
                checkpoints.add(new Pair(cp2, new Pair("lineEnd", null)));
            } else {
                if (isLastLine) checkpoints.add(new Pair(cp1, new Pair("sprint", null)));
                checkpoints.add(new Pair(cp2, new Pair("lineBegin", null)));
                checkpoints.add(new Pair(cp1, new Pair("lineEnd", null)));
            }
            northToSouth = !northToSouth;
        }

        if (checkpoints.isEmpty()) {
            // FallBack in case everything is built
            checkpoints.add(new Pair(mc.player.getEntityPos(), new Pair("sprint", null)));
        } else if (sprintFirst) {
            // Set the first checkpoint to sprint
            Vec3d firstVec3 = checkpoints.removeFirst().getLeft();
            checkpoints.addFirst(new Pair(firstVec3, new Pair("sprint", null)));
        }
    }

    private void minePath(int lineX) {
        checkpoints.clear();

        // If no block is to be mined, immediately finish
        boolean lineFinished = true;
        for (int x = lineX; x >= lineX-1; x--) {
            for (int z = 0; z < upperMapLayer[0].length; z++) {
                for (int y = 0; y < upperMapLayer[0][0].length; y++) {
                    if (!miningBlockState(getActiveMapCorner().add(x, y, z)).isAir()) {
                        lineFinished = false;
                    }
                }
            }
        }
        if (lineFinished) {
            checkpoints.add(new Pair(mc.player.getEntityPos(), new Pair("mineFinished", null)));
            return;
        }

        Vec3d cp1 = getActiveMapCorner().toCenterPos().add(lineX-0.5f, -0.5f, -1);
        Vec3d cp2 = getActiveMapCorner().toCenterPos().add(lineX-0.5f, -0.5f, lowerMapLayer[0].length-1);
        if (mc.player.getZ() < lowerMapCorner.getZ() + lowerMapLayer[0].length / 2) {
            checkpoints.add(new Pair(cp1, new Pair("mineBegin", null)));
            checkpoints.add(new Pair(cp2, new Pair("mineEnd", null)));
        } else {
            checkpoints.add(new Pair(cp2, new Pair("mineBegin", null)));
            checkpoints.add(new Pair(cp1, new Pair("mineEnd", null)));
        }
    }

    private void fillMapPath() {
        state = State.Walking;
        setInterval(new Pair<>(0, -1));

        Pair<BlockPos, Vec3d> bestChest = getBestChest(Items.CARTOGRAPHY_TABLE);
        checkpoints.add(new Pair(bestChest.getRight(), new Pair("mapMaterialChest", bestChest.getLeft())));
        checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(129,-1,-2), new Pair("sprint", null)));
        checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(129,-3.5,1), new Pair("sprint", null)));
        checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(125,-3.5,116), new Pair("fillMap", null)));
    }

    private void loadFirstLinePath() {
        checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(125,-3.5,12), new Pair("sprint", null)));
        checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(128,-3.5,15), new Pair("awaitSuppression", null)));
        state = State.Walking;
    }

    private void suppressPath() {
        state = State.Walking;
        int xOffset = 125 + suppressedLines*2;
        List<Integer> suppressionCheckpoints = Arrays.asList(15, 41, 65, 89, 113);

        if (suppressedLines <= 64) {
            // When player is south of the center, walk checkpoints south-to-north, otherwise north-to-south
            if (mc.player.getZ() > lowerMapCorner.getZ() + lowerMapLayer[0].length/2) {
                suppressionCheckpoints = suppressionCheckpoints.reversed();
            }
            // Generate Suppression Path
            for (Integer suppressionCheckpoint : suppressionCheckpoints) {
                Vec3d entry = lowerMapCorner.toCenterPos().add(xOffset + 1.4f,-3.5, suppressionCheckpoint);
                checkpoints.add(new Pair(entry, new Pair("startSneak", null)));
                checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(xOffset,-3.5, suppressionCheckpoint), new Pair("stopSneak", null)));
                checkpoints.add(new Pair(entry, new Pair("sprint", null)));
            }
            // Go to next suppression line
            checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(xOffset + 2,-3.5, suppressionCheckpoints.getLast()), new Pair("awaitSuppression", null)));
        } else {
            suppressedLines = -1;
            timeoutTicks = mapLoadTimeout.get();
            // Finished Suppression. Swap away & lock the finished map
            checkpoints.add(new Pair(mc.player.getEntityPos(), new Pair("swapMap", null)));
            checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(129,-3.5,suppressionCheckpoints.getFirst()), new Pair("sprint", null)));
            checkpoints.add(new Pair(lowerMapCorner.toCenterPos().add(129,-1,-2), new Pair("sprint", null)));
            checkpoints.add(new Pair(cartographyTable.getRight(), new Pair<>("cartographyTable", null)));
        }
    }

    private boolean initialSetup() {
        //Check if requirements to start building are met
        if (materialDict.isEmpty()) {
            warning("No Material Chests selected!");
            return false;
        }
        if (toolSet.isEmpty()) {
            warning("No Tool Chests selected!");
            return false;
        }
        if (mapMaterialChests.isEmpty()) {
            warning("No Map Chests selected!");
            return false;
        }
        if (fillerBlock.get() == null) {
            warning("Filler-block setting must be set!");
            return false;
        }
        if (lowerMapCorner == null || upperMapCorner == null) {
            warning("The lower map area has not been selected.");
            return false;
        }
        if (availableSlots.isEmpty() && !setupSlots()) return false;
        MapAreaCache.reset(getActiveMapCorner(), true);
        return true;
    }

    private void startBuilding() {
        if (SlaveSystem.isSlave) {
            // The master owns ordering and map safety.  A slave never starts a regular batch
            // from a click on its own: doing so races the master's priming command and can leave
            // the two bots on different batch numbers.
            state = State.StandBy;
            info("§7Step-checker slave ready; waiting for the master's step protocol.");
            return;
        }
        beginStepMasterRun();
    }

    /** Start the measured master-side lifecycle once registration/configuration is complete. */
    private void beginStepMasterRun() {
        if (!initialSetup()) return;
        if (SlaveSystem.slaves.size() != 1) {
            warning("Step-checker requires exactly one connected slave before starting (connected: "
                + SlaveSystem.slaves.size() + ").");
            return;
        }
        if (stepMasterStage != StepMasterStage.IDLE && stepMasterStage != StepMasterStage.ABORTED) {
            warning("Step-checker master is already running in " + stepMasterStage + ".");
            return;
        }

        stepMasterBatch = -1;
        stepMasterWaitTicks = 0;
        stepMasterDwellTicks = 0;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        stepMasterCloseBreakSent = false;
        stepSlaveReadyBatch = -1;
        stepSlaveBreak2DoneBatch = -1;
        stepSlaveDoneBatch = -1;
        stepSlaveReadyPriming = false;
        stepSlaveReadyClosing = false;
        stepSlaveClosingDone = false;
        stepMapSwapPending = false;
        stepStowMap();

        if (stepFindWorkingMapSlot() >= 0) {
            stepPinWorkingMap();
            // Resume: if a progress file exists we continue from the saved batch, skipping
            // priming and all already-completed batches (crash/restart recovery)
            int resumeBatch = loadStepProgress();
            if (resumeBatch > 0) {
                info("§aStep-checker: resuming from batch " + resumeBatch + " (progress file).");
                stepMasterBatch = resumeBatch - 1;  // beginStepBatch will increment it
                beginStepBatch(resumeBatch);
            } else {
                beginStepPrimingRun();
            }
        } else if (findEmptyMapSlot() >= 0) {
            beginStepMapFill();
        } else {
            // No map in inventory: walk to the map chest and fetch one
            Pair<BlockPos, Vec3d> mapChest = getBestChest(Items.CARTOGRAPHY_TABLE);
            if (mapChest != null && mapChest.getLeft() != null) {
                stepMasterStage = StepMasterStage.FETCH_MAP;
                stepMasterWaitTicks = 0;
                checkpoints.clear();
                checkpoints.add(new Pair(mapChest.getRight(), new Pair("stepFetchMap", mapChest.getLeft())));
                state = State.Walking;
                info("§aStep-checker: walking to the map chest to get an empty map.");
            } else {
                error("Step-checker needs a map but no map chest is registered.");
                stepMasterStage = StepMasterStage.ABORTED;
                state = State.StandBy;
            }
        }
    }

    // --- Progress persistence (crash/restart resume) ---

    private File stepProgressFile() {
        return new File(new File(mapFolder, "_configs"), "step_checkered_progress.json");
    }

    private void saveStepProgress(int batch) {
        try {
            File file = stepProgressFile();
            file.getParentFile().mkdirs();
            java.io.FileWriter writer = new java.io.FileWriter(file);
            writer.write("{\"batch\":" + batch + ",\"mapId\":" + stepWorkingMapId + "}\n");
            writer.close();
        } catch (Exception e) {
            if (debugPrints.get()) warning("Could not save step progress: " + e.getMessage());
        }
    }

    private int loadStepProgress() {
        try {
            File file = stepProgressFile();
            if (!file.exists()) return 0;
            String json = new String(java.nio.file.Files.readAllBytes(file.toPath()));
            int batchStart = json.indexOf("\"batch\":") + 8;
            int batchEnd = json.indexOf(",", batchStart);
            if (batchEnd < 0) batchEnd = json.indexOf("}", batchStart);
            return Integer.parseInt(json.substring(batchStart, batchEnd).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void clearStepProgress() {
        try {
            stepProgressFile().delete();
        } catch (Exception ignored) {
        }
    }

    /** RESET: abort the slave, clear progress, re-fetch a map, start from batch 0. */
    private void resetStepRun() {
        info("§cStep-checker RESET: clearing everything and starting from scratch.");
        // 1) Tell the slave to clear its batch
        if (!SlaveSystem.slaves.isEmpty()) {
            stepSendToSlave("abort");
            info("§7Abort command sent to the slave - it will break and deposit everything.");
        }
        // 2) Clear the progress file so the next start begins from batch 0
        clearStepProgress();
        // 3) Reset the master state
        stepMasterStage = StepMasterStage.IDLE;
        stepMasterBatch = -1;
        checkpoints.clear();
        state = State.StandBy;
        stepStowMap();
        stepWorkingMapId = -1;
        Utils.setForwardPressed(false);
        info("§aReset complete. Enable the module again to start a fresh run.");
    }

    /** Fill one empty map at the measured real checkpoint before any build or sweep begins. */
    private void beginStepMapFill() {
        stepMasterStage = StepMasterStage.FILL_MAP;
        stepMasterWaitTicks = 0;
        checkpoints.clear();
        Vec3d fillSpot = stepCheckpoint(125, 115);
        checkpoints.add(new Pair<>(fillSpot, new Pair<>("stepFillMap", null)));
        state = State.Walking;
        stepStowMap();
        info("§aStep-checker: walking to the fill checkpoint for the working map.");
    }

    private void beginStepPrimingRun() {
        stepMasterStage = StepMasterStage.WAIT_PRIMING_BUILD;
        stepMasterWaitTicks = 0;
        stepSlaveReadyPriming = false;
        stepSendToSlave("prime");
        stepQueueSafeRoute(2, "stepPrimingSafe");
        stepStowMap();
        info("§aStep-checker: priming build requested; waiting at the frozen checkpoint.");
    }

    private void beginStepBatch(int batch) {
        if (batch < 0 || batch > 62) {
            error("Invalid step-checker batch " + batch + ".");
            abortStepMaster("invalid batch");
            return;
        }
        stepMasterBatch = batch;
        stepMasterStage = StepMasterStage.WAIT_BATCH_BUILD;
        stepMasterWaitTicks = 0;
        stepSlaveReadyBatch = stepSlaveReadyBatch == batch ? batch : -1;
        stepSlaveBreak2DoneBatch = -1;
        stepSlaveDoneBatch = -1;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        stepSendToSlave("build:" + batch);
        // The master is allowed to cross intermediate real platforms with the map stowed;
        // only cp(batch+2) is the frozen wait position for this window.
        stepQueueSafeRoute(batch + 2, "stepBatchSafe");
        stepStowMap();
        info("§aStep-checker batch " + batch + " build requested.");
    }

    private void beginStepClosingRun() {
        stepMasterStage = StepMasterStage.WAIT_CLOSING_BUILD;
        stepMasterWaitTicks = 0;
        stepSlaveReadyClosing = false;
        stepSendToSlave("close");
        stepQueueSafeRoute(64, "stepClosingSafe");
        stepStowMap();
        info("§aStep-checker closing pair 63 build requested at x=253.");
    }

    private void stepSendToSlave(String command) {
        if (SlaveSystem.slaves.size() != 1) {
            abortStepMaster("the slave disconnected");
            return;
        }
        if (debugPrints.get()) info("§3[dbg] TCP send→slave: §b" + command);
        SlaveSystem.sendToSlave(SlaveSystem.slaves.get(0), "step:" + command);
    }

    /** Real platform/checkpoint coordinate: x is the platform's relative block coordinate. */
    private Vec3d stepCheckpoint(int relativeX, int relativeZ) {
        return new Vec3d(
            lowerMapCorner.getX() + relativeX + 0.5,
            lowerMapCorner.getY(),
            lowerMapCorner.getZ() + relativeZ + 0.5);
    }

    /**
     * Queue a route over real platforms only.  A route to cp(n) may cross cp(n-1), so include
     * every intermediate platform instead of cutting diagonally across the walkway.
     */
    private void stepQueueSafeRoute(int targetCheckpoint, String finalAction) {
        int target = Math.clamp(targetCheckpoint, 0, 64);
        double currentX = mc.player == null ? lowerMapCorner.getX() + 125.5 : mc.player.getX();
        int currentIndex = Math.clamp((int) Math.round((currentX - lowerMapCorner.getX() - 125.5) / 2.0), 0, 64);
        checkpoints.clear();
        int direction = Integer.compare(target, currentIndex);
        if (direction == 0) {
            checkpoints.add(new Pair<>(stepCheckpoint(125 + 2 * target, STEP_CHECKPOINT_Z[0]), new Pair<>(finalAction, null)));
            return;
        }
        for (int index = currentIndex + direction; ; index += direction) {
            String action = index == target ? finalAction : "stepTransit";
            checkpoints.add(new Pair<>(stepCheckpoint(125 + 2 * index, STEP_CHECKPOINT_Z[0]), new Pair<>(action, null)));
            if (index == target) break;
        }
        state = State.Walking;
    }

    private void stepQueueSweep(int lane, int startSpot, int endSpot) {
        checkpoints.clear();
        int direction = Integer.compare(endSpot, startSpot);
        for (int spot = startSpot; ; spot += direction) {
            checkpoints.add(new Pair<>(stepCheckpoint(lane, STEP_CHECKPOINT_Z[spot]), new Pair<>("stepDwell", null)));
            if (spot == endSpot) break;
        }
        state = State.Walking;
        stepMasterDwellTicks = 0;
        stepMasterSweepCompletionPending = false;
    }

    private void stepStartSweep(int lane, int startSpot, int endSpot) {
        stepPendingSweepLane = lane;
        stepPendingSweepStart = startSpot;
        stepPendingSweepEnd = endSpot;
        stepSweepQueuePending = true;
        stepMasterWaitTicks = 0;
        stepTryQueuePendingSweep();
    }

    private void stepTryQueuePendingSweep() {
        if (!stepSweepQueuePending) return;
        if (!stepEnsureMapHeld()) {
            if (++stepMasterWaitTicks > 100) {
                abortStepMaster("working map could not be held");
            }
            return;
        }
        stepSweepQueuePending = false;
        stepQueueSweep(stepPendingSweepLane, stepPendingSweepStart, stepPendingSweepEnd);
    }

    private void stepHandleSweepComplete() {
        stepMasterSweepCompletionPending = false;
        if (debugPrints.get()) info("§3[dbg] sweep complete in stage §b" + stepMasterStage);
        switch (stepMasterStage) {
            case PRIMING_WALK -> {
                stepStowMap();
                beginStepBatch(0);
            }
            case BATCH_WALK_A -> {
                stepMasterStage = StepMasterStage.BATCH_WALK_B;
                stepStartSweep(2 * stepMasterBatch + 127, 4, 0);
            }
            case BATCH_WALK_B -> {
                stepStowMap();
                stepMasterStage = StepMasterStage.WAIT_BREAK_2;
                stepMasterWaitTicks = 0;
                stepQueueSafeRoute(stepMasterBatch + 2, "stepBreak2Point");
            }
            case CLOSING_WALK -> {
                stepStowMap();
                stepMasterStage = StepMasterStage.WAIT_CLOSING_BREAK;
                stepMasterWaitTicks = 0;
                stepCloseMapReady();
            }
            default -> abortStepMaster("unexpected sweep completion in " + stepMasterStage);
        }
    }

    private void stepCloseMapReady() {
        if (stepMasterStage != StepMasterStage.WAIT_CLOSING_BREAK) return;
        if (stepMasterCloseBreakSent) return;
        stepMasterCloseBreakSent = true;
        stepSendToSlave("closebreak");
        info("§aFinal map walk complete; closing pair 63 recycle requested.");
    }

    /** Client-thread master scheduler.  This intentionally bypasses the legacy printer loop. */
    private void tickStepMaster() {
        // Process any pending chest-inventory response FIRST - the step scheduler's early
        // return skips the legacy toBeHandledInvPacket block, so without this the master
        // opens a chest and never takes the items (the "empty map not withdrawn" bug)
        if (toBeHandledInvPacket != null) {
            if (timeoutTicks > 0) {
                timeoutTicks--;
                Utils.setForwardPressed(false);
                return;
            }
            handleInventoryPacket(toBeHandledInvPacket);
            toBeHandledInvPacket = null;
            if (closeNextInvPacket) {
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                closeNextInvPacket = false;
            }
            return;
        }

        // General pause timer (set by requeueStepWait, interactions, etc.)
        if (timeoutTicks > 0) {
            timeoutTicks--;
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            return;
        }

        if (mc.player == null || mc.world == null) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            return;
        }
        if (SlaveSystem.slaves.size() != 1 && stepMasterStage != StepMasterStage.ABORTED
            && stepMasterStage != StepMasterStage.READY_TO_LOCK
            && stepMasterStage != StepMasterStage.LOCK_WALK
            && stepMasterStage != StepMasterStage.LOCKING) {
            abortStepMaster("the slave disconnected");
            return;
        }

        if (stepMasterStage == StepMasterStage.LOCKING) {
            tickStepLock();
            return;
        }

        // FETCH_MAP: if the empty map was taken from the chest, chain to the fill flow
        if (stepMasterStage == StepMasterStage.FETCH_MAP && state != State.AwaitMapChestResponse) {
            if (findEmptyMapSlot() >= 0) {
                beginStepMapFill();
                return;
            }
            if (stepFindWorkingMapSlot() >= 0) {
                stepPinWorkingMap();
                int resumeBatch = loadStepProgress();
                if (resumeBatch > 0) {
                    info("§aStep-checker: resuming from batch " + resumeBatch + ".");
                    stepMasterBatch = resumeBatch - 1;
                    beginStepBatch(resumeBatch);
                } else {
                    beginStepPrimingRun();
                }
                return;
            }
        }

        if (stepMasterStage == StepMasterStage.WAIT_FILLED_MAP) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            stepStowMap();
            if (stepPinWorkingMap()) {
                beginStepPrimingRun();
            } else if (++stepMasterWaitTicks > 300) {
                abortStepMaster("empty map did not produce a filled map within 15 seconds");
            }
            return;
        }

        if (stepSweepQueuePending) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            stepTryQueuePendingSweep();
            return;
        }

        if (stepMasterDwellTicks > 0) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            if (stepMasterStage == StepMasterStage.PRIMING_WALK
                || stepMasterStage == StepMasterStage.BATCH_WALK_A
                || stepMasterStage == StepMasterStage.BATCH_WALK_B
                || stepMasterStage == StepMasterStage.CLOSING_WALK) {
                if (!stepEnsureMapHeld()) {
                    abortStepMaster("working map disappeared during a sweep");
                    return;
                }
            }
            stepMasterDwellTicks--;
            if (stepMasterDwellTicks == 0 && stepMasterSweepCompletionPending) {
                stepHandleSweepComplete();
            }
            return;
        }

        if (stepMasterStage == StepMasterStage.WAIT_CLOSING_BREAK && stepSlaveClosingDone) {
            stepSlaveClosingDone = false;
            stepMasterStage = StepMasterStage.READY_TO_LOCK;
            stepStowMap();
            state = State.StandBy;
            info("§aStep-checker print complete. Map is stowed and ready for cartography locking.");
            return;
        }

        if (stepMasterStage == StepMasterStage.WAIT_BREAK_ALL
            && stepMasterBreakAllSent
            && stepSlaveDoneBatch == stepMasterBatch) {
            int completedBatch = stepMasterBatch;
            stepSlaveDoneBatch = -1;
            saveStepProgress(completedBatch + 1);   // persist resume point
            if (completedBatch < 62) {
                beginStepBatch(completedBatch + 1);
            } else {
                beginStepClosingRun();
            }
            return;
        }

        if (stepMasterStage == StepMasterStage.FILL_MAP && checkpoints.isEmpty()) {
            if (++stepMasterWaitTicks > 100) {
                abortStepMaster("could not select the empty map at the fill checkpoint");
            } else {
                useEmptyMapAtFillSpot();
            }
            return;
        }

        if (stepMasterStage == StepMasterStage.READY_TO_LOCK || stepMasterStage == StepMasterStage.ABORTED) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            stepStowMap();
            return;
        }

        if (checkpoints.isEmpty()) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            return;
        }

        Utils.setForwardPressed(true);
        Utils.setSneakPressed(false);
        Vec3d goal = checkpoints.getFirst().getLeft();
        Vec3d horizontalGoal = goal.add(0, mc.player.getY() - goal.y, 0);
        if (PlayerUtils.distanceTo(horizontalGoal) < checkpointBuffer.get()) {
            String action = checkpoints.removeFirst().getRight().getLeft();
            handleStepMasterCheckpoint(action);
            return;
        }

        double lookZ = goal.z;
        if (PlayerUtils.distanceTo(goal) > 2) {
            lookZ = mc.player.getZ() + Math.max(Math.min(goal.z - mc.player.getZ(), 1), -1);
        }
        mc.player.setYaw((float) Rotations.getYaw(new Vec3d(goal.x, goal.y, lookZ)));
        if (sprinting.get() != SprintMode.Off) mc.player.setSprinting(true);
    }

    private void handleStepMasterCheckpoint(String action) {
        switch (action) {
            case "stepFillMap" -> useEmptyMapAtFillSpot();
            case "stepFetchMap" -> {
                // Open the map chest; the AwaitMapChestResponse handler takes the map + pane.
                // After the map is taken, tickStepMaster sees FETCH_MAP + an empty map and
                // chains to beginStepMapFill (walk to the fill spot and use it).
                if (stepMasterStage == StepMasterStage.FETCH_MAP) {
                    Pair<BlockPos, Vec3d> chest = getBestChest(Items.CARTOGRAPHY_TABLE);
                    if (chest != null && chest.getLeft() != null) {
                        state = State.AwaitMapChestResponse;
                        interactWithBlock(chest.getLeft());
                    } else {
                        abortStepMaster("map chest not found");
                    }
                }
            }
            case "stepPrimingSafe" -> {
                if (stepMasterStage == StepMasterStage.WAIT_PRIMING_BUILD && stepSlaveReadyPriming) {
                    stepSlaveReadyPriming = false;
                    stepMasterStage = StepMasterStage.PRIMING_WALK;
                    stepStartSweep(125, 0, 4);
                } else if (stepMasterStage == StepMasterStage.WAIT_PRIMING_BUILD) {
                    requeueStepWait(2, "stepPrimingSafe");
                }
            }
            case "stepBatchSafe" -> {
                if (stepMasterStage == StepMasterStage.WAIT_BATCH_BUILD && stepSlaveReadyBatch == stepMasterBatch) {
                    stepMasterStage = StepMasterStage.BATCH_WALK_A;
                    stepStartSweep(2 * stepMasterBatch + 127, 0, 4);
                } else if (stepMasterStage == StepMasterStage.WAIT_BATCH_BUILD) {
                    requeueStepWait(stepMasterBatch + 2, "stepBatchSafe");
                }
            }
            case "stepBreak2Point" -> {
                if (stepMasterStage != StepMasterStage.WAIT_BREAK_2) return;
                if (!stepMasterBreak2Sent) {
                    stepMasterBreak2Sent = true;
                    stepSendToSlave("break2");
                }
                stepMasterStage = StepMasterStage.WAIT_BREAK_ALL;
                stepQueueSafeRoute(stepMasterBatch + 1, "stepBreakAllPoint");
                stepStowMap();
                info("§aBatch " + stepMasterBatch + ": break(2) requested; moving to the next real checkpoint.");
            }
            case "stepBreakAllPoint" -> {
                if (stepMasterStage != StepMasterStage.WAIT_BREAK_ALL) return;
                if (stepSlaveBreak2DoneBatch == stepMasterBatch && !stepMasterBreakAllSent) {
                    stepMasterBreakAllSent = true;
                    stepSendToSlave("breakall");
                    info("§aBatch " + stepMasterBatch + ": break-all requested.");
                } else if (stepMasterStage == StepMasterStage.WAIT_BREAK_ALL) {
                    requeueStepWait(stepMasterBatch + 1, "stepBreakAllPoint");
                }
            }
            case "stepClosingSafe" -> {
                if (stepMasterStage == StepMasterStage.WAIT_CLOSING_BUILD && stepSlaveReadyClosing) {
                    stepMasterStage = StepMasterStage.CLOSING_WALK;
                    stepStartSweep(253, 0, 4);
                } else if (stepMasterStage == StepMasterStage.WAIT_CLOSING_BUILD) {
                    requeueStepWait(64, "stepClosingSafe");
                }
            }
            case "stepLockTable" -> {
                if (stepMasterStage != StepMasterStage.LOCK_WALK) return;
                if (cartographyTable == null) {
                    abortStepMaster("cartography table disappeared before locking");
                    return;
                }
                BlockHitResult hit = new BlockHitResult(
                    cartographyTable.getLeft().toCenterPos(),
                    Utils.getInteractionSide(cartographyTable.getLeft()),
                    cartographyTable.getLeft(),
                    false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
                stepMasterStage = StepMasterStage.LOCKING;
                stepLockStep = 1;
                stepLockDelay = 10;
                Utils.setForwardPressed(false);
                Utils.setSneakPressed(false);
            }
            case "stepTransit" -> {
                // Crossing a platform while the map is stowed is intentionally a no-op.
            }
            case "stepDwell" -> {
                if (stepMasterStage != StepMasterStage.PRIMING_WALK
                    && stepMasterStage != StepMasterStage.BATCH_WALK_A
                    && stepMasterStage != StepMasterStage.BATCH_WALK_B
                    && stepMasterStage != StepMasterStage.CLOSING_WALK) return;
                if (!stepEnsureMapHeld()) {
                    abortStepMaster("working map disappeared at a sweep checkpoint");
                    return;
                }
                stepMasterDwellTicks = STEP_DWELL_TICKS;
                stepMasterSweepCompletionPending = checkpoints.isEmpty();
            }
            default -> {
                if (debugPrints.get()) info("Unknown step master checkpoint action: " + action);
            }
        }
    }

    private void requeueStepWait(int checkpointIndex, String action) {
        checkpoints.addFirst(new Pair<>(stepCheckpoint(125 + 2 * Math.clamp(checkpointIndex, 0, 64), STEP_CHECKPOINT_Z[0]), new Pair<>(action, null)));
        state = State.Walking;
        // Pause 1 second between re-checks - without this the master is already AT the
        // checkpoint when it re-queues, consumes it instantly, and oscillates back and forth
        timeoutTicks = 20;
    }

    private void tickStepLock() {
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        if (stepLockDelay > 0) {
            stepLockDelay--;
            return;
        }
        if (!(mc.player.currentScreenHandler instanceof net.minecraft.screen.CartographyTableScreenHandler table)) {
            abortStepMaster("cartography table screen did not open");
            return;
        }
        switch (stepLockStep) {
            case 1 -> {
                if (!moveIntoStepTableSlot(table, 0, stack -> {
                    Integer id = mapIdOf(stack);
                    return id != null && id == stepWorkingMapId
                        && !stack.contains(DataComponentTypes.CUSTOM_NAME);
                })) {
                    abortStepMaster("pinned working map is not in the inventory");
                    return;
                }
                stepLockStep = 2;
                stepLockDelay = 6;
            }
            case 2 -> {
                if (!moveIntoStepTableSlot(table, 1, stack -> stack.getItem() == Items.GLASS_PANE)) {
                    abortStepMaster("no glass pane is available for the cartography lock");
                    return;
                }
                stepLockStep = 3;
                stepLockDelay = 10;
            }
            case 3 -> {
                if (table.getSlot(2).getStack().isEmpty()) {
                    abortStepMaster("cartography table produced no locked-map result");
                    return;
                }
                mc.interactionManager.clickSlot(table.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
                stepLockStep = 4;
                stepLockDelay = 8;
            }
            default -> {
                mc.player.closeHandledScreen();
                stepLockStep = -1;
                stepMasterStage = StepMasterStage.READY_TO_LOCK;
                state = State.StandBy;
                info("§aCartography lock complete: the locked copy was taken from the table.");
            }
        }
    }

    private boolean moveIntoStepTableSlot(net.minecraft.screen.CartographyTableScreenHandler table,
                                          int targetSlot,
                                          java.util.function.Predicate<ItemStack> predicate) {
        for (int slot = 3; slot < table.slots.size(); slot++) {
            ItemStack stack = table.getSlot(slot).getStack();
            if (stack.isEmpty() || !predicate.test(stack)) continue;
            mc.interactionManager.clickSlot(table.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(table.syncId, targetSlot, 0, SlotActionType.PICKUP, mc.player);
            return true;
        }
        return false;
    }

    private Integer mapIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof FilledMapItem)) return null;
        var id = stack.get(DataComponentTypes.MAP_ID);
        return id == null ? null : id.id();
    }

    private int findEmptyMapSlot() {
        for (int slot = 0; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).getItem() == Items.MAP) return slot;
        }
        return -1;
    }

    /** Returns the pinned map slot, or the first unnamed filled map when pinning has not begun. */
    private int stepFindWorkingMapSlot() {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            Integer id = mapIdOf(stack);
            if (id == null || stack.contains(DataComponentTypes.CUSTOM_NAME)) continue;
            if (stepWorkingMapId < 0 || id == stepWorkingMapId) return slot;
        }
        return -1;
    }

    private boolean stepPinWorkingMap() {
        if (stepWorkingMapId >= 0) return stepFindWorkingMapSlot() >= 0;
        int slot = stepFindWorkingMapSlot();
        if (slot < 0) return false;
        Integer id = mapIdOf(mc.player.getInventory().getStack(slot));
        if (id == null) return false;
        stepWorkingMapId = id;
        info("§aWorking map pinned: id=" + id + ".");
        return true;
    }

    /** Select the pinned map without ever selecting a named reference map. */
    private boolean stepEnsureMapHeld() {
        if (stepWorkingMapId < 0 && !stepPinWorkingMap()) return false;
        Integer held = mapIdOf(mc.player.getMainHandStack());
        if (held != null && held == stepWorkingMapId) return true;

        int slot = stepFindWorkingMapSlot();
        if (slot < 0) return false;
        if (slot < 9) {
            mc.player.getInventory().setSelectedSlot(slot);
            stepMapSwapPending = false;
            return mapIdOf(mc.player.getMainHandStack()) != null
                && mapIdOf(mc.player.getMainHandStack()) == stepWorkingMapId;
        }
        if (!stepMapSwapPending) {
            swapPlayerInventoryToHotbar(slot, mc.player.getInventory().getSelectedSlot());
            stepMapSwapPending = true;
        }
        return false;
    }

    /** Holster the filled map before any slave build/break or station transit. */
    private void stepStowMap() {
        if (mc.player == null) return;
        if (mapIdOf(mc.player.getMainHandStack()) == null) return;
        int selected = mc.player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            if (mapIdOf(mc.player.getInventory().getStack(slot)) == null) {
                mc.player.getInventory().setSelectedSlot(slot);
                stepMapSwapPending = false;
                return;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (mapIdOf(mc.player.getInventory().getStack(slot)) == null) {
                swapPlayerInventoryToHotbar(slot, selected);
                stepMapSwapPending = true;
                return;
            }
        }
        warning("No non-map inventory slot is available to stow the working map.");
    }

    private void swapPlayerInventoryToHotbar(int inventorySlot, int hotbarSlot) {
        if (inventorySlot < 9 || inventorySlot >= 36 || hotbarSlot < 0 || hotbarSlot >= 9) return;
        int handlerSlot = inventorySlot;
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            handlerSlot,
            hotbarSlot,
            SlotActionType.SWAP,
            mc.player);
    }

    private void useEmptyMapAtFillSpot() {
        int slot = findEmptyMapSlot();
        if (slot < 0) {
            abortStepMaster("the empty map disappeared before the fill checkpoint");
            return;
        }
        if (slot >= 9) {
            if (!stepMapSwapPending) {
                swapPlayerInventoryToHotbar(slot, mc.player.getInventory().getSelectedSlot());
                stepMapSwapPending = true;
            }
            return;
        }
        mc.player.getInventory().setSelectedSlot(slot);
        mc.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(
            Hand.MAIN_HAND,
            Utils.getNextInteractID(),
            mc.player.getYaw(),
            mc.player.getPitch()));
        stepMasterStage = StepMasterStage.WAIT_FILLED_MAP;
        stepMasterWaitTicks = 0;
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        info("§7Empty map used at the fill checkpoint; waiting for the filled result.");
    }

    private void abortStepMaster(String reason) {
        if (stepMasterStage == StepMasterStage.ABORTED) return;
        stepMasterStage = StepMasterStage.ABORTED;
        checkpoints.clear();
        state = State.StandBy;
        stepSweepQueuePending = false;
        stepMasterDwellTicks = 0;
        stepMasterSweepCompletionPending = false;
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        stepStowMap();
        error("Step-checker master stopped: " + reason + ".");
    }

    /**
     * Phase 1 - the slave's full batch route. Every entry/exit crosses the map area boundary
     * on the NORTH side only; both staging points sit one block outside the area (headroom -
     * no layer above the bot's head at the ends). Route per batch k:
     *   north corridor -> lane 2 (2k+2) north staging -> print pass south ->
     *   lane -1 (2k-1) south end: WAIT for the master's break(2) call ->
     *   break Y2/Y3 of lanes 2k,2k+1 walking lane -1 north ->
     *   lane -1 north end: WAIT for the master's break-all call ->
     *   deposit all building blocks at the chests (via the north corridor), then break the
     *   whole window from lane 2 north -> south (collecting every drop; deposit trip if the
     *   inventory fills) -> walk back north along lane 2 picking up any leftovers ->
     *   exit north. All later batches reuse the same relative checkpoints (+2 lanes).
     */
    private void beginBatch(int k) {
        if (!initialSetup()) {
            state = State.StandBy;
            return;
        }
        currentBatch = k;
        batchPassRetries = 0;
        breakStage = 0;
        printRepairPending = false;
        break2Permitted = false;
        breakAllPermitted = false;
        currentBuildPlan = assembleBatch(k);
        info("§aBatch " + k + ": §b" + currentBuildPlan.size() + "§7 blocks planned. Restocking...");

        checkpoints.clear();
        state = State.Walking;
        double walkX = lowerMapCorner.getX() + 2.0 * k + 4.0;
        double laneMinus1X = lowerMapCorner.getX() + 2.0 * k + 1.5;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;

        // Restock materials (+ pickaxe if missing) and food BEFORE entering the print route.
        // refillBuildingInventory() adds its first chest at the head of the queue, so the
        // actual batch route is appended only after preparation is complete.
        queueStepBuildPreparation(walkX);

        // The batch route (consumed front-to-back as the slave progresses)
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("printDone", null)));
        checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
        checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
        checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
        prependNorthCorridor(walkX);
    }

    /**
     * Queues the survival preparation shared by regular batches, priming, and closing.  The
     * queue order is material chests -> pickaxe chest (if needed) -> food -> hand setup; callers
     * then append their print route.  Every trip remains on the north corridor.
     */
    private void queueStepBuildPreparation(double stagingX) {
        HashMap<Item, Integer> requiredItems = getRequiredItems();
        Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInfo =
            Utils.getInvInformation(requiredItems, availableSlots);
        refillBuildingInventory(invInfo.getRight());
        boolean materialRestockQueued = !restockList.isEmpty();

        if (!hasGoodPickaxe()) {
            Item pickaxe = findRegisteredPickaxeItem();
            if (pickaxe != null) {
                restockList.add(Triple.of(pickaxe, 1, 1));
                // If materials already have a chest at the head, endRestocking() will promote
                // this entry after them.  With no material restock, add its first chest now.
                if (!materialRestockQueued) addClosestRestockCheckpoint();
            }
        }
        if (!hasFoodInInventory() && foodChest != null) {
            checkpoints.add(new Pair(foodChest.getRight(), new Pair("foodChest", foodChest.getLeft())));
        }
        double northZ = lowerMapCorner.getZ() - 2;
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("swapOffhand", null)));
    }

    /** Consume one queued master command on the slave's client thread. */
    private void processPendingStepCommands() {
        String command;
        synchronized (pendingStepCommands) {
            command = pendingStepCommands.peekFirst();
        }
        if (command == null) return;

        String[] parts = command.split(":");
        String verb = parts[0];
        switch (verb) {
            case "prime" -> {
                if (currentBuildPlan != null && currentBatch < 0) {
                    removePendingStepCommand();
                } else if (state == State.StandBy || currentBuildPlan == null) {
                    removePendingStepCommand();
                    beginPriming();
                }
            }
            case "build" -> {
                if (parts.length < 2) {
                    removePendingStepCommand();
                    return;
                }
                Integer batch = parseStepBatch(parts[1]);
                if (batch == null) {
                    removePendingStepCommand();
                    return;
                }
                if (currentBuildPlan != null && currentBatch == batch) {
                    removePendingStepCommand();
                } else if (state == State.StandBy || currentBuildPlan == null) {
                    removePendingStepCommand();
                    beginBatch(batch);
                }
            }
            case "break2" -> {
                if (state == State.AwaitBreakPermission2) {
                    removePendingStepCommand();
                    break2Permitted = true;
                }
            }
            case "breakall" -> {
                if (state == State.AwaitBreakPermissionAll) {
                    removePendingStepCommand();
                    breakAllPermitted = true;
                }
            }
            case "close" -> {
                if (state == State.StandBy || currentBuildPlan == null) {
                    removePendingStepCommand();
                    beginClosing();
                }
            }
            case "closebreak" -> {
                if (state == State.AwaitClosingBreak) {
                    removePendingStepCommand();
                    startClosingBreak();
                }
            }
            case "abort" -> {
                removePendingStepCommand();
                stepAbort();
            }
            default -> removePendingStepCommand();
        }
    }

    private void removePendingStepCommand() {
        synchronized (pendingStepCommands) {
            if (!pendingStepCommands.isEmpty()) pendingStepCommands.removeFirst();
        }
    }

    /** Priming places only recessive pairs 0 and 1; the regular batch 0 overwrites it later. */
    private void beginPriming() {
        if (!initialSetup()) {
            state = State.StandBy;
            return;
        }
        currentBatch = -1;
        breakStage = 0;
        printRepairPending = false;
        currentBuildPlan = assemblePriming();
        checkpoints.clear();
        state = State.Walking;

        // Same center-lane position as the regular batches (boundary between lanes 1&2):
        // corner.x + 2.0 for the 4-lane window starting at lane 0
        double stagingX = lowerMapCorner.getX() + 2.0;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;
        queueStepBuildPreparation(stagingX);
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), southZ), new Pair("stepPrimingReady", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("stepPrimingWait", null)));
        prependNorthCorridor(stagingX);
        info("§aPriming: §b" + currentBuildPlan.size() + "§7 recessive blocks planned.");
    }

    /** Build the final dominant pair 63 after regular batch 62 has been recycled. */
    /** Master RESET: break every remaining batch block, deposit the items, return to standby. */
    private void stepAbort() {
        info("§cRESET: full-area clear starting.");
        // Force-clear ALL active state — any phase the slave was in is now dead
        checkpoints.clear();
        printRepairPending = false;
        depositBacklogSlots.clear();
        restockBacklogSlots.clear();
        restockList.clear();
        batchPassRetries = 0;
        currentBuildPlan = null;
        breakStage = 0;
        collectingStragglers = false;
        nextBlockPos = null;
        toBeHandledInvPacket = null;
        closeNextInvPacket = false;
        toBeSwappedSlot = -1;
        fullSweepActive = false;
        timeoutTicks = 0;
        interactTimeout = 0;
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);

        // Full-area sweep: the slave walks to vantage points where its render distance
        // covers the 128x128 print box and breaks every non-air block (spatial awareness,
        // nearest-first). Vantage points: north center (covers the north half) → map
        // center (covers everything). Entry always from the north side.
        double centerX = lowerMapCorner.getX() + 64.0;
        double northZ = lowerMapCorner.getZ() - 2.0;
        double mapCenterZ = lowerMapCorner.getZ() + 64.0;

        checkpoints.add(new Pair(new Vec3d(centerX, lowerMapCorner.getY(), northZ), new Pair("fullSweepStart", null)));
        checkpoints.add(new Pair(new Vec3d(centerX, lowerMapCorner.getY(), mapCenterZ), new Pair("fullSweepCenter", null)));

        // Route via the north corridor (never cross the map area diagonally)
        double corridorZ = lowerMapCorner.getZ() - 2;
        if (Math.abs(mc.player.getZ() - corridorZ) > 1.5) {
            checkpoints.add(0, new Pair(new Vec3d(mc.player.getX(), lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        }

        state = State.Walking;
        sweepBudget = 600;   // ~30 seconds max for the full sweep
        fullSweepCenterChecked = false;   // center vantage not yet visited
        info("§7Walking to north-center vantage point, then breaking all remaining blocks.");
    }

    /**
     * Scans the print box (128 lanes x 128 rows x y99-102) for the nearest non-air block
     * from the bot's current position. Only checks loaded chunks (render distance).
     */
    private BlockPos findNearestPrintBoxBlock() {
        if (mc.world == null || mc.player == null || lowerMapCorner == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3d eye = mc.player.getEyePos();
        int y0 = lowerMapCorner.getY();
        int y1 = lowerMapCorner.getY() + 3;

        // Scan in expanding rings from the player's position for efficiency
        int centerZ = mc.player.getBlockZ();
        int centerX = mc.player.getBlockX();
        for (int radius = 0; radius <= 64; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;  // ring only
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    // Only check blocks inside the print box
                    if (x < lowerMapCorner.getX() || x >= lowerMapCorner.getX() + 128) continue;
                    if (z < lowerMapCorner.getZ() || z >= lowerMapCorner.getZ() + 128) continue;
                    for (int y = y0; y <= y1; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!miningBlockState(pos).isAir()) {
                            double dist = eye.distanceTo(pos.toCenterPos());
                            if (dist < bestDist) { bestDist = dist; best = pos; }
                        }
                    }
                }
            }
            if (best != null) return best;   // nearest found in this ring
        }
        return best;   // null = nothing in range
    }

    /** Checks if the print box is completely empty (all air) within loaded chunks. */
    private boolean printBoxEmptyInRange() {
        return findNearestPrintBoxBlock() == null;
    }

    private int countRemainingPlanBlocks() {
        if (currentBuildPlan == null) return 0;
        int count = 0;
        for (BlockPos pos : currentBuildPlan.keySet()) {
            if (!miningBlockState(pos).isAir()) count++;
        }
        return count;
    }

    private void beginClosing() {
        if (!initialSetup()) {
            state = State.StandBy;
            return;
        }
        currentBatch = 63;
        breakStage = 0;
        printRepairPending = false;
        currentBuildPlan = assembleClosing();
        checkpoints.clear();
        state = State.Walking;

        // Closing = dominant pair 63 on lanes 126-127 (2-lane window, not 4):
        // the slave walks the center of that window, NOT the master's x=253 sweep lane
        double stagingX = lowerMapCorner.getX() + 127.0;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;
        queueStepBuildPreparation(stagingX);
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), southZ), new Pair("stepClosingReady", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("stepClosingWait", null)));
        prependNorthCorridor(stagingX);
        info("§aClosing: §b" + currentBuildPlan.size() + "§7 dominant blocks planned; sweep must use x=253.");
    }

    private void startClosingBreak() {
        breakStage = 4;
        checkpoints.clear();
        state = State.Walking;
        double stagingX = lowerMapCorner.getX() + 253.5;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("breakAllBegin", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), southZ), new Pair("breakAllDone", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("stepClosingDone", null)));
        prependNorthCorridor(stagingX);
        info("§aClosing: recycling pair 63 after the final map verification.");
    }

    private void finishClosing() {
        ArrayList<BlockPos> remaining = new ArrayList<>();
        if (currentBuildPlan != null) {
            for (BlockPos pos : currentBuildPlan.keySet()) {
                if (!miningBlockState(pos).isAir()) remaining.add(pos);
            }
        }
        if (!remaining.isEmpty()) {
            error("Closing recycle left " + remaining.size() + " blocks standing; stopped for diagnosis.");
            state = State.StandBy;
            stepMasterStage = StepMasterStage.ABORTED;
            Utils.setForwardPressed(false);
            return;
        }
        currentBuildPlan = null;
        breakStage = 0;
        state = State.StandBy;
        Utils.setForwardPressed(false);
        SlaveSystem.sendMessageToMaster("step:closed");
    }

    /** Route to the batch via the north corridor (one block outside the area): out north,
     *  along the corridor, then the queued batch checkpoints take over. The slave never
     *  crosses the map area except entering/exiting at the north side. */
    private void prependNorthCorridor(double targetX) {
        double corridorZ = lowerMapCorner.getZ() - 2;
        checkpoints.addFirst(new Pair(new Vec3d(targetX, lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        if (Math.abs(mc.player.getZ() - corridorZ) > 1.5) {
            checkpoints.addFirst(new Pair(new Vec3d(mc.player.getX(), lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        }
    }

    /** Placement check at the south end of the print pass. Missing blocks are NOT retried
     *  here - the repair path places them from lane -1 while walking north (different
     *  vantage), then one more lane 2 pass, before the break(2) wait. */
    private void verifyPrintPass() {
        int missing = 0;
        int wrong = 0;
        for (Map.Entry<BlockPos, Block> entry : currentBuildPlan.entrySet()) {
            BlockState worldState = miningBlockState(entry.getKey());
            if (worldState.isAir()) missing++;
            else if (!worldState.getBlock().equals(entry.getValue())) wrong++;
        }
        if (missing == 0 && wrong == 0) {
            info("§aBatch " + currentBatch + " printed: §b" + currentBuildPlan.size() + "§7 blocks placed (world-verified).");
            return;
        }
        printRepairPending = true;
        info("§eBatch " + currentBatch + " print has §c" + missing + "§e missing / §c" + wrong + "§e wrong - repairing from lane -1 on the way north, then one more lane 2 pass.");
    }

    /** Strict world-truth gate used by priming and closing, where a repair route is undefined. */
    private boolean verifyCurrentPlan(String label) {
        if (currentBuildPlan == null) {
            error(label + " has no active placement plan.");
            state = State.StandBy;
            return false;
        }
        int missing = 0;
        int wrong = 0;
        for (Map.Entry<BlockPos, Block> entry : currentBuildPlan.entrySet()) {
            BlockState worldState = miningBlockState(entry.getKey());
            if (worldState.isAir()) missing++;
            else if (!worldState.getBlock().equals(entry.getValue())) wrong++;
        }
        if (missing == 0 && wrong == 0) return true;
        error(label + " placement incomplete: " + missing + " missing, " + wrong + " wrong. Stopped.");
        state = State.StandBy;
        Utils.setForwardPressed(false);
        return false;
    }

    /** Strict check after the repair passes: proceed or stop for diagnosis. */
    private boolean verifyPrintPass2() {
        int missing = 0;
        int wrong = 0;
        for (Map.Entry<BlockPos, Block> entry : currentBuildPlan.entrySet()) {
            BlockState worldState = miningBlockState(entry.getKey());
            if (worldState.isAir()) missing++;
            else if (!worldState.getBlock().equals(entry.getValue())) wrong++;
        }
        if (missing == 0 && wrong == 0) {
            info("§aBatch " + currentBatch + " repair complete - plan fully placed.");
            return true;
        }
        error("Batch " + currentBatch + " print still incomplete after the repair passes: " + missing + " missing, " + wrong + " wrong. Stopped for diagnosis.");
        state = State.StandBy;
        Utils.setForwardPressed(false);
        return false;
    }

    /** break(2) completeness: Y2/Y3 of the window's west 2 lanes must be gone. */
    private boolean verifyBreak2() {
        int westX = lowerMapCorner.getX() + 2 * currentBatch;
        ArrayList<BlockPos> remaining = new ArrayList<>();
        for (BlockPos pos : currentBuildPlan.keySet()) {
            if (pos.getX() >= westX + 2 || pos.getY() < lowerMapCorner.getY() + 2) continue;
            if (!miningBlockState(pos).isAir()) remaining.add(pos);
        }
        if (remaining.isEmpty()) {
            info("§aBatch " + currentBatch + " break(2) complete (west Y2/Y3 down).");
            return true;
        }
        if (batchPassRetries >= 2) {
            error("Batch " + currentBatch + " break(2) incomplete after retry: " + remaining.size() + " blocks left. Stopped.");
            state = State.StandBy;
            Utils.setForwardPressed(false);
            return false;
        }
        batchPassRetries++;
        info("§eBatch " + currentBatch + " break(2) leftover (" + remaining.size() + ") - walking back for a retry pass.");
        double laneMinus1X = lowerMapCorner.getX() + 2.0 * currentBatch + 1.5;
        checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), lowerMapCorner.getZ() + 128.7), new Pair("sprint", null)));
        checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), lowerMapCorner.getZ() - 2), new Pair("break2Done", null)));
        state = State.Mining;
        return false;
    }

    /** On the master's break-all call: deposit every building block first (north corridor
     *  trip), then break the whole window from lane 2 north -> south, collect everything,
     *  and exit north along lane 2 picking up any leftovers. */
    private void startBreakAll() {
        breakStage = 4;
        checkpoints.clear();
        double walkX = lowerMapCorner.getX() + 2.0 * currentBatch + 4.0;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;

        Pair<BlockPos, Vec3d> chest = getBestDepositChest();
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("breakAllBegin", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("breakAllDone", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("batchExit", null)));
        if (chest != null) {
            checkpoints.addFirst(new Pair(chest.getRight(), new Pair("depositBuilding", chest.getLeft())));
        }
        prependNorthCorridor(chest != null ? chest.getRight().x : walkX);
        state = State.Walking;
        Utils.setForwardPressed(false);
        info("§aBreak-all: depositing building blocks first, then recycling batch " + currentBatch + ".");
    }

    /** A chest to deposit building blocks into: one already stocking a held material, else
     *  the nearest registered material chest. */
    private Pair<BlockPos, Vec3d> getBestDepositChest() {
        Pair<BlockPos, Vec3d> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Item item : materialDict.keySet()) {
            boolean carrying = false;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = mc.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && stack.getItem().equals(item)) { carrying = true; break; }
            }
            if (!carrying) continue;
            for (Pair<BlockPos, Vec3d> chest : materialDict.get(item)) {
                double dist = PlayerUtils.distanceTo(chest.getRight());
                if (dist < bestDist) { bestDist = dist; best = chest; }
            }
        }
        if (best != null) return best;
        for (ArrayList<Pair<BlockPos, Vec3d>> chests : materialDict.values()) {
            for (Pair<BlockPos, Vec3d> chest : chests) {
                double dist = PlayerUtils.distanceTo(chest.getRight());
                if (dist < bestDist) { bestDist = dist; best = chest; }
            }
        }
        return best;
    }

    private boolean containsChest(ArrayList<Pair<BlockPos, Vec3d>> chests, BlockPos pos) {
        for (Pair<BlockPos, Vec3d> chest : chests) {
            if (chest.getLeft().equals(pos)) return true;
        }
        return false;
    }

    /** Does the OPEN chest have room for this stack (empty slot or mergeable partial)? */
    private boolean chestHasRoomFor(ItemStack stack) {
        if (stack.isEmpty()) return true;
        int chestSlots = mc.player.currentScreenHandler.slots.size() - 36;
        for (int i = 0; i < chestSlots; i++) {
            ItemStack chestStack = mc.player.currentScreenHandler.slots.get(i).getStack();
            if (chestStack.isEmpty()) return true;
            if (chestStack.isStackable() && chestStack.getItem().equals(stack.getItem())
                && chestStack.getCount() < chestStack.getMaxCount()) return true;
        }
        return false;
    }

    /** A DIFFERENT chest for the remaining carried materials, nearest first: prefers a chest
     *  that stocks a carried item; falls back to ANY registered chest not marked full - and
     *  registers that fallback chest as a new stocking location for the item (remembered for
     *  the rest of the run, so future deposits/restocks use it too). */
    private Pair<BlockPos, Vec3d> getNextDepositChest() {
        ArrayList<Item> carried = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
            if (materialDict.containsKey(stack.getItem()) && !carried.contains(stack.getItem())) carried.add(stack.getItem());
        }
        Pair<BlockPos, Vec3d> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Item item : carried) {
            for (Pair<BlockPos, Vec3d> chest : materialDict.get(item)) {
                if (lastInteractedChest != null && chest.getLeft().equals(lastInteractedChest)) continue;
                if (fullDepositChests.contains(chest.getLeft())) continue;
                double dist = PlayerUtils.distanceTo(chest.getRight());
                if (dist < bestDist) { bestDist = dist; best = chest; }
            }
        }
        if (best != null) return best;
        // Fallback: any registered chest with (hopefully) space - remember it for the item
        for (Item item : carried) {
            for (ArrayList<Pair<BlockPos, Vec3d>> chests : materialDict.values()) {
                for (Pair<BlockPos, Vec3d> chest : chests) {
                    if (fullDepositChests.contains(chest.getLeft())) continue;
                    if (!containsChest(materialDict.getOrDefault(item, new ArrayList<>()), chest.getLeft())) {
                        materialDict.computeIfAbsent(item, k -> new ArrayList<>()).add(chest);
                        info("§7Registered " + chest.getLeft().toShortString() + " as a new location for " + item.getName().getString() + ".");
                    }
                    return chest;
                }
            }
        }
        return null;
    }

    /** Mid-run supply trip: exit north from whatever lane the bot is on, refill at the chest
     *  station (materials while printing; a pickaxe when it broke/went missing), then resume
     *  the interrupted pass. All targets are world-truth based, so resuming is exact. */
    private void pauseForRestockTrip(boolean needPickaxe) {
        // Cooldown: prevent the supply-run trigger from re-firing every tick while the
        // bot is still Walking toward the chest (tryPlacingBlock sees no materials →
        // calls this again → clears the route → loops forever without moving)
        if (restockCooldown > 0) return;
        restockCooldown = 100;   // 5 seconds before another supply run can trigger

        info("§eMid-batch supply run via the north checkpoint.");
        int savedStage = breakStage;
        breakStage = 0;
        checkpoints.clear();

        if (savedStage == 0) {
            HashMap<Item, Integer> requiredItems = getRequiredItems();
            Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInfo = Utils.getInvInformation(requiredItems, availableSlots);
            refillBuildingInventory(invInfo.getRight());
        }
        if (needPickaxe && !hasGoodPickaxe() && !toolSet.isEmpty()) {
            for (ItemStack tool : toolSet) {
                if (tool.isIn(ItemTags.PICKAXES)) { restockList.add(Triple.of(tool.getItem(), 1, 1)); break; }
            }
        }
        resumeBatchRoute(savedStage);
        prependNorthCorridor(lowerMapCorner.getX() + 2.0 * currentBatch + 4.0);
        state = State.Walking;
        Utils.setForwardPressed(false);
    }

    /** Rebuilds the remaining batch route after an interruption (supply run / deposit trip). */
    private void resumeBatchRoute(int stage) {
        double walkX = lowerMapCorner.getX() + 2.0 * currentBatch + 4.0;
        double laneMinus1X = lowerMapCorner.getX() + 2.0 * currentBatch + 1.5;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;
        switch (stage) {
            case 0 -> {
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("printDone", null)));
                checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
                checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
                checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
            }
            case 2 -> {
                checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), southZ), new Pair("break2Resume", null)));
                checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
                checkpoints.add(new Pair(new Vec3d(laneMinus1X, lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
            }
            default -> {
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("breakAllBegin", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("breakAllDone", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("batchExit", null)));
            }
        }
    }

    /** Inventory filled up mid-break: deposit trip through the north corridor, then resume. */
    private void pauseBreakAllForDeposit() {
        info("§eInventory full - depositing building blocks before continuing the break.");
        breakStage = 0;
        checkpoints.clear();
        resumeBatchRoute(4);
        Pair<BlockPos, Vec3d> chest = getBestDepositChest();
        if (chest != null) {
            checkpoints.addFirst(new Pair(chest.getRight(), new Pair("depositBuilding", chest.getLeft())));
            prependNorthCorridor(chest.getRight().x);
        } else {
            prependNorthCorridor(lowerMapCorner.getX() + 2.0 * currentBatch + 4.0);
        }
        state = State.Walking;
    }

    /** Final check at the north exit: everything recycled, pickaxe back to the offhand. */
    private void finishBatch() {
        // COMBINED SWEEP: check both remaining blocks and ground items at once.
        // sweepTick handles whichever is nearest (block → break, item → collect).
        // No separate retry walks, no zigzag — one spatial-awareness pass.
        int remainingBlocks = countRemainingPlanBlocks();
        int groundItems = countGroundItemsInWindow();
        if ((remainingBlocks > 0 || groundItems > 0) && batchPassRetries < 8) {
            batchPassRetries++;
            collectingStragglers = true;
            sweepBudget = 200;   // ~10 seconds of combined sweep
            checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("sweepTick", null)));
            state = State.Walking;
            if (remainingBlocks > 0) {
                info("§eBatch " + currentBatch + " SWEEP: " + remainingBlocks + " blocks + "
                    + groundItems + " items - spatial targeting.");
            } else {
                info("§eBatch " + currentBatch + " SWEEP: " + groundItems + " ground items.");
            }
            return;
        }
        collectingStragglers = false;
        info("§aBatch " + currentBatch + " COMPLETE: printed and fully recycled.");
        int finishedBatch = currentBatch;
        currentBuildPlan = null;
        breakStage = 0;
        state = State.StandBy;
        Utils.setForwardPressed(false);
        SlaveSystem.sendMessageToMaster("step:done:" + finishedBatch);
    }

    /** The batch the renderer shows: the ACTIVE batch while a run is in progress,
     *  otherwise the manually selected render-batch setting (preview mode). */
    private int effectiveRenderBatch() {
        return currentBatch >= 0 ? currentBatch : renderBatch.get();
    }
            /** Shared: nearest ground-collided pickup-able ItemEntity near the player, within the
     *  print box plus a margin (items bounce outside the exact box when blocks break).
     *  Used by both the batch SWEEP and the full-area RESET — one function, no duplicates. */
    private ItemEntity findNearestGroundItem(int margin) {
        if (mc.world == null || mc.player == null) return null;
        ItemEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!itemEntity.groundCollision || itemEntity.cannotPickup() || itemEntity.getOwner() != null) continue;
            if (itemEntity.getX() < lowerMapCorner.getX() - margin || itemEntity.getX() > lowerMapCorner.getX() + 128 + margin) continue;
            if (itemEntity.getZ() < lowerMapCorner.getZ() - margin || itemEntity.getZ() > lowerMapCorner.getZ() + 128 + margin) continue;
            if (itemEntity.getY() < lowerMapCorner.getY() - 3 || itemEntity.getY() > lowerMapCorner.getY() + 5) continue;
            double dist = mc.player.getEyePos().distanceTo(itemEntity.getEntityPos());
            if (dist < nearestDist) { nearestDist = dist; nearest = itemEntity; }
        }
        return nearest;
    }

    /** Shared: walk to a ground item for vanilla proximity pickup (used by SWEEP and RESET). */
    private void targetGroundItem(ItemEntity item, String reEvalAction) {
        checkpoints.clear();
        checkpoints.add(new Pair(new Vec3d(item.getX(), item.getY(), item.getZ()), new Pair(reEvalAction, null)));
        state = State.Walking;
    }

    /** Count ground-collided pickup-able item entities within the current batch window. */
    private int countGroundItemsInWindow() {
        if (mc.world == null || mc.player == null) return 0;
        int westX = lowerMapCorner.getX() + 2 * Math.max(currentBatch, 0);
        int count = 0;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!itemEntity.groundCollision || itemEntity.cannotPickup() || itemEntity.getOwner() != null) continue;
            if (itemEntity.getX() < westX - 2 || itemEntity.getX() > westX + 5) continue;
            if (itemEntity.getZ() < lowerMapCorner.getZ() - 3 || itemEntity.getZ() > lowerMapCorner.getZ() + 130) continue;
            if (itemEntity.getY() < lowerMapCorner.getY() - 2 || itemEntity.getY() > lowerMapCorner.getY() + 5) continue;
            count++;
        }
        return count;
    }

    /** Nearest unplaced plan block within reach that does not intersect the player's body. */
    private BlockPos getNextBatchPlacementPos() {
        if (currentBuildPlan == null) return null;
        // PRINT BEHIND (HARD 2-BLOCK GAP): the bot must be at least 2 blocks PAST a block
        // row before it can be placed. No blocks appear at, beside, or within 2 blocks
        // ahead of the bot — only well behind it in the walking direction. The bot enters
        // the north checkpoint, walks 2+ blocks south, then starts placing what it passed.
        double botZ = mc.player.getZ();
        double goalZ = !checkpoints.isEmpty() ? checkpoints.getFirst().getLeft().z : botZ;
        boolean walkingSouth = goalZ > botZ;
        // Only place when the bot has entered the print area (past the first row)
        if (walkingSouth && botZ < lowerMapCorner.getZ() + 1.5) return null;
        if (!walkingSouth && botZ > lowerMapCorner.getZ() + 126.5) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : currentBuildPlan.keySet()) {
            if (!miningBlockState(pos).isAir()) continue;
            // Skip positions on placement cooldown (litematica-style: don't hammer the
            // same failed block — let the timeout expire, then retry)
            if (placementCooldowns.getOrDefault(pos, 0) > 0) continue;
            // HARD GATE: skip everything within 2 blocks of or ahead of the walking direction
            if (walkingSouth && pos.getZ() >= botZ - 2) continue;
            if (!walkingSouth && pos.getZ() <= botZ + 2) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist > interactionRange.get()) continue;
            if (mc.player.getBoundingBox().intersects(new Box(pos))) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    /** break target: nearest still-standing plan block in reach (break(2) = west lanes' Y2/Y3 only). */
    private BlockPos getNextBatchBreakPos() {
        if (currentBuildPlan == null) return null;
        boolean break2Only = breakStage == 2;
        int westX = lowerMapCorner.getX() + 2 * currentBatch;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : currentBuildPlan.keySet()) {
            if (miningBlockState(pos).isAir()) continue;
            if (break2Only && (pos.getX() >= westX + 2 || pos.getY() < lowerMapCorner.getY() + 2)) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist > interactionRange.get()) continue;
            if (dist < bestDist) { bestDist = dist; best = pos; }
        }
        return best;
    }

    private boolean hasFoodInInventory() {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) return true;
        }
        return false;
    }

    /** Finds a PICKAXE in the inventory (no other tool qualifies); -1 if none.
     *  Usable-durability pickaxes are preferred; a worn one is only a fallback. */
    private int findPickaxeSlot() {
        int wornSlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.PICKAXES)) continue;
            if (ToolUtils.getRemainingDamage(stack) >= minDurability.get()) return slot;
            if (wornSlot == -1) wornSlot = slot;
        }
        return wornSlot;
    }

    /** True when a pickaxe with usable durability is carried (reserved slot 8 or anywhere in
     *  the inventory). A worn pickaxe below min-durability does NOT count - it must be
     *  replaced, not reused. */
    private boolean hasGoodPickaxe() {
        ItemStack reserved = mc.player.getInventory().getStack(8);
        if (reserved.isIn(ItemTags.PICKAXES) && ToolUtils.getRemainingDamage(reserved) >= minDurability.get()) return true;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.PICKAXES) && ToolUtils.getRemainingDamage(stack) >= minDurability.get()) return true;
        }
        return false;
    }

    /** Any registered pickaxe: from the tool set or any material-chest registration. */
    private Item findRegisteredPickaxeItem() {
        for (ItemStack tool : toolSet) {
            if (tool.isIn(ItemTags.PICKAXES)) return tool.getItem();
        }
        for (Item item : materialDict.keySet()) {
            if (new ItemStack(item).isIn(ItemTags.PICKAXES)) return item;
        }
        return null;
    }

    /** Walk to the restock station's pickaxe chest, take one pickaxe, then come back to the
     *  staging checkpoint and continue. No failure path - the chest is registered. */
    private void restockPickaxeTrip(Vec3d stagingPos) {
        info("§eNo pickaxe - fetching one from the restock station.");
        Item pickaxe = findRegisteredPickaxeItem();
        if (pickaxe == null) {
            warning("No pickaxe registered in any chest - continuing without one.");
            return;
        }
        Pair<BlockPos, Vec3d> chest = getBestChest(pickaxe);
        if (chest.getLeft() == null) return;
        restockList.add(Triple.of(pickaxe, 1, 1));
        checkpoints.add(0, new Pair(new Vec3d(stagingPos.x, stagingPos.y, stagingPos.z), new Pair("batchBegin", null)));
        checkpoints.add(0, new Pair(chest.getRight(), new Pair("refill", chest.getLeft())));
        state = State.Walking;
    }

    /** Pickaxe into its reserved hotbar slot 8 (from wherever it is in the inventory). */
    private void movePickaxeToSlot8() {
        if (mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) return;
        int slot = findPickaxeSlot();
        if (slot == -1) return;
        Utils.performSwap(slot, 8);
        info("Pickaxe moved to slot 8.");
    }

    /** A food stack into the offhand (eaten from there); swaps out whatever it holds. */
    private void moveFoodToOffhand() {
        if (mc.player.getOffHandStack().contains(DataComponentTypes.FOOD)) return;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) {
                int screenSlot = slot < 9 ? 36 + slot : slot;
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, screenSlot, 40, SlotActionType.SWAP, mc.player);
                info("Food to the offhand.");
                return;
            }
        }
    }

    /** Hand setup at the batch staging: pickaxe into reserved slot 8, food into the offhand,
     *  main hand free for placing. */
    private void setupHands() {
        movePickaxeToSlot8();
        moveFoodToOffhand();
    }

    /** Ensures the reserved pickaxe slot 8 is filled before a breaking pass (the mining
     *  branch selects it directly). */
    private void ensurePickaxeSlot8() {
        movePickaxeToSlot8();
    }

    private void finishMineLine() {
        SlaveSystem.sendMessageToMaster("placeStatus:" + workingInterval.getLeft() + ":"
            + workingInterval.getRight() + ":" + false + ":" + workOnUpper);
        setMiningFinished();

        if (!collectBlocks.get() || isInventoryFull()) return;

        AtomicBoolean isEmpty = new AtomicBoolean(true);
        forEachMapBlock((x, z, y, blockState, block) -> {
            if (blockState != null && !blockState.isAir()) {
                isEmpty.set(false);
                return false;
            }
            return true;
        }, true);

        if (isEmpty.get()) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof ItemEntity itemEntity
                    && itemEntity.getOwner() == null
                    && itemEntity.getY() >= getActiveMapCorner().getY()
                    && itemEntity.getY() <= getActiveMapCorner().getY() + 2
                    && itemEntity.getX() <= lowerMapCorner.getX() + 128
                    && itemEntity.getX() >= lowerMapCorner.getX()
                    && itemEntity.getZ() <= lowerMapCorner.getZ() + 129
                    && itemEntity.getZ() >= lowerMapCorner.getZ() - 2) {
                    // Cap the collectionPos to avoid the player trying to walk into walls
                    Vec3d collectionPos = new Vec3d(
                        Math.clamp(itemEntity.getX(), lowerMapCorner.getX() + 0.4f, lowerMapCorner.getX() + 127.6f),
                        getActiveMapCorner().getY(),
                        Math.min(itemEntity.getZ(), lowerMapCorner.getZ() + 128.6f));
                    checkpoints.addFirst(new Pair<>(collectionPos, new Pair<>("sprint", null)));
                }
            }
        }
    }

    private void setLineFinished(int xStart, int xEnd, boolean placed, boolean isUpper) {
        xEnd = Math.min(workingInterval.getRight(), xEnd);
        SlaveSystem.mapCompletionState.setInterval(xStart, xEnd, placed, isUpper);
        SlaveSystem.sendMessageToMaster("placeStatus:" + xStart + ":" +xEnd + ":" + placed + ":" + isUpper);
    }

    private void setMiningFinished() {
        if (!SlaveSystem.isSlave) {
            // Master Mining Mode
            if (lowerMiningCommand.isEmpty()) {
                state = State.AwaitNBTFile;
                return;
            }
            masterMiningReady = true;
            return;
        }
        state = State.StandBy;
        Utils.setForwardPressed(false);
        SlaveSystem.sendMessageToMaster("finished");
    }

    private BlockPos getActiveMapCorner() {
        return workOnUpper ? upperMapCorner : lowerMapCorner;
    }

    private Block[][][] getActiveMapLayer() {
        return workOnUpper ? upperMapLayer : lowerMapLayer;
    }

    // Inventory Management

    private boolean setupSlots() {
        availableSlots = Utils.getAvailableSlots(materialDict);
        // Hotbar slot 8 is RESERVED for the pickaxe - never used for materials or swaps
        availableSlots.remove(Integer.valueOf(8));
        for (int slot : availableSlots) {
            if (slot < 9) {
                availableHotBarSlots.add(slot);
            }
        }
        if (debugPrints.get()) info("Inventory slots available for building: " + availableSlots);
        if (availableHotBarSlots.isEmpty()) {
            warning("No free slots found in hot-bar!");
            availableSlots.clear();
            toggle();
            return false;
        }
        if (availableSlots.size() < 2) {
            warning("You need at least 2 free inventory slots!");
            availableSlots.clear();
            toggle();
            return false;
        }
        return true;
    }

    private HashMap<Item, Integer> getRequiredItems() {
        //Calculate the next items to restock
        HashMap<Item, Integer> requiredItems = new HashMap<>();
        if (currentBuildPlan != null) {
            // Batch mode: count one per still-missing plan block (live world truth), capped by
            // the available inventory slots like the legacy collector
            for (Map.Entry<BlockPos, Block> entry : currentBuildPlan.entrySet()) {
                if (!miningBlockState(entry.getKey()).isAir()) continue;
                Item material = entry.getValue().asItem();
                int oldCount = requiredItems.getOrDefault(material, 0);
                requiredItems.put(material, oldCount + 1);
                if (Utils.stacksRequired(requiredItems.values()) > availableSlots.size()) {
                    requiredItems.put(material, oldCount);
                }
            }
            return requiredItems;
        }
        forEachMapBlock((x, z, y, blockState, block) -> {
            if (blockState.isAir() && getActiveMapLayer()[x][z][y] != null) {
                // ChatUtils.info("Add material for: " + mapCorner.add(x, y, z).toShortString());
                Item material = block.asItem();
                if (!requiredItems.containsKey(material)) requiredItems.put(material, 0);
                requiredItems.put(material, requiredItems.get(material) + 1);
                // Check if the item fits into inventory. If not, undo the last increment and return
                if (Utils.stacksRequired(requiredItems.values()) > availableSlots.size()) {
                    requiredItems.put(material, requiredItems.get(material) - 1);
                    return false;
                }
            }
            return true;
        });
        return requiredItems;
    }

    private void swapIntoHotbar(int slot) {
        Map<Item, Integer> itemSlot = new HashMap<>();
        Map<Item, Integer> itemDistance = new HashMap<>();
        Map<Item, Integer> itemFrequency = new HashMap<>();

        int targetSlot = availableHotBarSlots.get(0);

        // Scan hotbar
        for (int hotbarSlot : availableHotBarSlots) {
            ItemStack stack = mc.player.getInventory().getStack(hotbarSlot);
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                itemSlot.put(item, hotbarSlot);
                itemDistance.put(item, -1); // -1 = never used
                itemFrequency.put(item, 0);
            } else {
                targetSlot = hotbarSlot;
                break;
            }
        }

        // PRIORITY 1: empty slot → instant choice
        if (mc.player.getInventory().getStack(targetSlot).isEmpty()) {
            Utils.performSwap(slot, targetSlot);
            return;
        }

        // Get blocks until next use of items in hotbar
        AtomicReference<Integer> blockCounter = new AtomicReference<>(0);
        forEachMapBlock((x, z, y, blockState, block) -> {
            if (!Utils.isInInterval(workingInterval, x)) return true;
            blockCounter.set(blockCounter.get() + 1);

            if (blockState.isAir() || block == null) return true;
            Item item = block.asItem();

            if (itemDistance.containsKey(item) &&
                itemDistance.get(item) == -1) {
                itemDistance.put(item, blockCounter.get());
            }
            return true;
        });

        // Count frequency of items in hotbar
        for (int hotbarSlot : availableHotBarSlots) {
            ItemStack stack = mc.player.getInventory().getStack(hotbarSlot);
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                itemFrequency.put(item, itemFrequency.get(item) + 1);
            }
        }

        // Choose best candidate
        Item bestItem = null;
        int bestDistance = -2; // lower than -1
        int bestFrequency = -1;

        for (Item item : itemSlot.keySet()) {
            int distance = itemDistance.get(item); // -1 = never used
            int frequency = itemFrequency.get(item);

            boolean better = false;

            // PRIORITY 2: never used (-1)
            if (distance == -1 && bestDistance != -1) {
                better = true;
            }
            // PRIORITY 3: hotbar frequency
            else if (frequency > bestFrequency) {
                better = true;
            }
            // PRIORITY 4: distance to next use
            else if (frequency == bestFrequency && distance > bestDistance && bestDistance != -1) {
                better = true;
            }

            if (better) {
                bestItem = item;
                bestDistance = distance;
                bestFrequency = frequency;
            }
        }

        if (bestItem != null) {
            targetSlot = itemSlot.get(bestItem);
        }

        Utils.performSwap(slot, targetSlot);
    }

    /** Total free item capacity across the inventory: empty slots count as a full stack each,
     *  partial stacks count their remaining headroom. Deposit trips only fire at ZERO -
     *  merging collected drops into partial stacks is free space and must not trigger them. */
    private int freeInventoryCapacity() {
        int capacity = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) { capacity += 64; continue; }
            if (stack.isStackable()) capacity += stack.getMaxCount() - stack.getCount();
        }
        return capacity;
    }

    private boolean isInventoryFull() {
        for (int slot : availableSlots) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return false;
        }
        return true;
    }

    // MapPrinter Interface for Slave Logic

    public void setLayer(boolean isUpper) {
        workOnUpper = isUpper;
    }

    public void setInterval(Pair<Integer, Integer> interval) {
        if (debugPrints.get()) info("set interval to " + interval.getLeft() + " " + interval.getRight());
        lastPlacedLine = interval.getLeft();
        workingInterval = interval;
    }

    public void pause() {
        if (!state.equals(State.AwaitSlaveContinue)) {
            oldState = state;
            state = State.AwaitSlaveContinue;
            Utils.setForwardPressed(false);
        }
    }

    public void start() {
        state = State.AwaitNBTFile;
    }

    public boolean getActivationReset() {
        return activationReset.get();
    }

    public void skipBuilding() {
        info("ToDo");
        startBuilding();
    }

    public void slaveFinished(String slave) {
        if (switchedToMiningSlaves.contains(slave)) return;
        // Transform Placing Slave to Mining Slave
        if (SlaveSystem.slaves.size() <= 2 || (SlaveSystem.slaves.size() == 3 && slave == SlaveSystem.slaves.get(1))) {
            SlaveSystem.sendToSlave(slave, "interval:" + -1 + ":" + -1);
            SlaveSystem.sendToSlave(slave, "mine:" + -1);
            SlaveSystem.finishedSlavesDict.put(slave, false);
            SlaveSystem.activeSlavesDict.put(slave, true);
            switchedToMiningSlaves.add(slave);
        }
    }

    public void mineLine(int line) {
        if (line < 0) {
            initialSetup();
            if (!prepareNextMapFile()) return;
            state = State.Walking;
            nextBlockPos = null;
            checkpoints.add(new Pair(mc.player.getEntityPos(), new Pair("calculateMiningRefill", null)));
            return;
        }
        if (!SlaveSystem.isSlave) {
            setInterval(new Pair<>(line*2, workingInterval.getRight()));
            minePath(workingInterval.getLeft() + 1);
        } else {
            setInterval(new Pair<>(Math.max(workingInterval.getLeft(), 0), line*2 + 1));
            minePath(workingInterval.getRight());
        }

        state = State.Mining;
    }

    /**
     * Resolves this bot's TCP role: username pinning wins (master-username / slave-username
     * matched against the session account), then bot-role, then auto.
     */
    private String resolveTcpRole() {
        // Username overrides win (optional), then the dropdown selection
        String self = mc != null ? mc.getSession().getUsername() : "";
        if (!masterUsername.get().isBlank() && masterUsername.get().trim().equalsIgnoreCase(self)) return "master";
        if (!slaveUsername.get().isBlank() && slaveUsername.get().trim().equalsIgnoreCase(self)) return "slave";
        return botRole.get() == BotRole.master ? "master"
            : botRole.get() == BotRole.slave ? "slave"
            : "auto";
    }

    private void updateTcpAddress() {
        if (mc == null || mc.world == null || !isActive()) return;
        if (tcpIp != null && tcpPort != null) {
            SlaveSystem.tcpRole = resolveTcpRole();
            SlaveSystem.setTcpAddress(tcpIp.get(), tcpPort.get());
        }
    }

    public void addError(BlockPos relativeBlockPos) {}

    // Path Change Check

    private void warnPathChanged() {
        if (checkpoints != null && !activationReset.get()) {
            String reString = isActive() ? "re" : "";
            warning("The custom path is only applied if the module is " + reString + "started with Activation Reset enabled!");
        }
    }

    // Config System

    private void saveConfig(File configFile) {
        if (configFile == null) {
            error("No config file name selected.");
            return;
        }
        if (cartographyTable == null || finishedMapChest == null || lowerMapCorner == null
            || upperMapCorner == null || anvil == null || foodChest == null
            || xpBottleChest == null || materialDict.isEmpty() || toolSet.isEmpty()) {
            error("Cannot save config: Missing required data.");
            return;
        }
        try {
            ConfigSerializer.writeToJson(
                configFile.toPath(),
                "step-checkered",
                null,
                cartographyTable,
                finishedMapChest,
                null,
                null,
                mapMaterialChests,
                null,
                lowerMapCorner,
                upperMapCorner,
                materialDict,
                toolSet,
                anvil,
                foodChest,
                xpBottleChest);
            Text configText = Text.literal(configFile.getName())
                .styled(style -> style
                    .withColor(Formatting.GREEN)
                    .withClickEvent(new ClickEvent.OpenFile(configFile.getAbsolutePath().toString()))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open config")))
                    .withUnderline(true));
            info(Text.literal("Successfully saved config to: ").formatted(Formatting.GRAY).append(configText));
        } catch (IOException e) {
            error("Failed to create config file.");
        }
    }

    private boolean loadConfig(File configFile) {
        if (configFile == null || !configFile.exists() || state == null) {
            warning("Could not find config file: "
                + (configFile == null ? "no file set" : configFile.getAbsolutePath()));
            return false;
        }
        List<State> allowedStates = List.of(
            State.SelectingChests,
            State.SelectingFinishedMapChest,
            State.SelectingAnvil,
            State.SelectingFoodChest,
            State.SelectingXpChest,
            State.SelectingTable,
            State.SelectingLowerMapArea,
            State.AwaitRegisterResponse
        );
        if (!allowedStates.contains(state)) {
            error("Can only load config during the registration phase.");
            return false;
        }

        try {
            ConfigDeserializer.ConfigData data =
                ConfigDeserializer.readFromJson(configFile.toPath());

            if (!data.type.equals("step-checkered")) {
                error("Config file is of type " + data.type + " and not 'step-checkered'.");
                return false;
            }
            if (data.cartographyTable == null || data.finishedMapChest == null || data.mapCorner == null
                || data.anvil == null || data.foodChest == null
                || data.xpBottleChest == null || data.materialDict.isEmpty() || toolSet == null) {
                error("Config file is missing required data.");
                return false;
            }
            this.cartographyTable = data.cartographyTable;
            this.finishedMapChest = data.finishedMapChest;
            this.anvil = data.anvil;
            this.foodChest = data.foodChest;
            this.xpBottleChest = data.xpBottleChest;
            this.mapMaterialChests = data.mapMaterialChests;
            this.lowerMapCorner = data.mapCorner;
            this.upperMapCorner = data.upperMapCorner != null ? data.upperMapCorner : data.mapCorner.up(2);
            MapAreaCache.reset(lowerMapCorner, true);
            verifyBatchAssembly();
            this.materialDict = data.materialDict;
            this.toolSet = data.toolSet;
            Text configText = Text.literal(configFile.getName())
                .styled(style -> style
                    .withColor(Formatting.GREEN)
                    .withClickEvent(new ClickEvent.OpenFile(configFile.getAbsolutePath().toString()))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open config")))
                    .withUnderline(true));
            info(Text.literal("Successfully loaded config: ").formatted(Formatting.GRAY).append(configText));
            info("§aInteract with the §bStart Block §aon the §bMaster Account §ato start printing.");
            state = State.SelectingChests;
        } catch (IOException e) {
            error("Failed to read config file: " + e.getMessage());
            return false;
        }
        return true;
    }

    // NBT file handling

    private boolean prepareNextMapFile() {
        mapFile = Utils.getNextMapFile(mapFolder, startedFiles, moveToFinishedFolder.get());

        if (mapFile == null) {
            if (disableOnFinished.get()) {
                info("§aAll nbt files finished");
                requestDisable();
            }
            return false;
        }
        if (!loadNBTFile()) {
            warning("Failed to read nbt file.");
            requestDisable();
            return false;
        }

        return true;
    }

    /** Deferred disable - toggle() called from inside onActivate makes Meteor re-enter the
     *  activation path recursively (StackOverflowError); the tick consumes this flag instead. */
    private void requestDisable() {
        pendingDisable = true;
    }

    private boolean loadNBTFile() {
        try {
            NbtSizeTracker sizeTracker = new NbtSizeTracker(0x20000000L, 100);
            NbtCompound nbt = NbtIo.readCompressed(mapFile.toPath(), sizeTracker);
            //Extracting the palette
            NbtList paletteList = (NbtList) nbt.get("palette");
            blockPaletteDict = Utils.getBlockPalette(paletteList);

            NbtList blockList = (NbtList) nbt.get("blocks");
            generateStepCheckerLayers(blockList, blockPaletteDict);
            return true;
        } catch (Exception e) {
            warning("Failed to load NBT file: " + e.getMessage());
            e.printStackTrace();
            requestDisable();
            return false;
        }
    }

    /**
     * Classic staircase NBT -> EvModder step-checker layers (W->E): a faithful Java port of the
     * PROVEN tools/manualrun/convert_step.py oracle (verified block-identical against Ev's own
     * step-checker export, 27,565 blocks / 65 phases). Per pair j (lanes 2j / 2j+1), pixel
     * class = (px+pz)&1 (0 = recessive, 1 = dominant):
     *   REC layer j - recessive colours at y0; Dark -> resin y1 on the north column; Flat ->
     *                the north (dominant) pixel's own material at y0 (pre-placed "borrow"
     *                shading the recessive Flat pixel south of it); resin when the north is
     *                transparent or the noobline.
     *   DOM layer j - dominant colours at y0 (idempotent over borrows); Dark -> resin y1
     *                north; Flat -> the north (recessive) pixel's colour at y0 (shade provider
     *                once its record is done); resin ditto.
     * Local coordinates: x 0..127, y 0/1, z -1..127 (z = -1 is the noobline filler row).
     */
    private void generateStepCheckerLayers(NbtList blockList, HashMap<Integer, Pair<Block, Integer>> blockPaletteDict) {
        // Center the schematic on the 128x129 footprint first (like the legacy printer's map
        // loader) - schematics exported with a coordinate offset would index out of bounds
        int minX = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < blockList.size(); i++) {
            Optional<NbtCompound> blockOpt = blockList.getCompound(i);
            if (blockOpt.isEmpty()) continue;
            NbtList pos = blockOpt.get().getList("pos").orElse(null);
            if (pos == null || pos.size() < 3) continue;
            Optional<Integer> xOpt = pos.getInt(0);
            Optional<Integer> zOpt = pos.getInt(2);
            if (xOpt.isEmpty() || zOpt.isEmpty()) continue;
            minX = Math.min(minX, xOpt.get());
            maxZ = Math.max(maxZ, zOpt.get());
        }
        int xOffset = minX == Integer.MAX_VALUE ? 0 : minX;
        int zOffset = maxZ == Integer.MIN_VALUE ? 0 : maxZ - 128;

        // Classic heightmap: cell (x 0..127, z 0..128) -> (block, topmost y); z = 0 is the noobline
        Pair<Block, Integer>[][] heightMap = new Pair[128][129];
        for (int i = 0; i < blockList.size(); i++) {
            Optional<NbtCompound> blockOpt = blockList.getCompound(i);
            if (blockOpt.isEmpty()) continue;

            NbtCompound block = blockOpt.get();

            Optional<Integer> blockIdOpt = block.getInt("state");
            if (blockIdOpt.isEmpty() || !blockPaletteDict.containsKey(blockIdOpt.get())) continue;

            NbtList pos = block.getList("pos").orElse(null);
            if (pos == null || pos.size() < 3) continue;

            Optional<Integer> xOpt = pos.getInt(0);
            Optional<Integer> yOpt = pos.getInt(1);
            Optional<Integer> zOpt = pos.getInt(2);
            if (xOpt.isEmpty() || yOpt.isEmpty() || zOpt.isEmpty()) continue;

            int x = xOpt.get() - xOffset;
            int z = zOpt.get() - zOffset;
            if (x < 0 || x >= 128 || z < 0 || z >= 129) continue;
            if (heightMap[x][z] == null || heightMap[x][z].getRight() < yOpt.get()) {
                heightMap[x][z] = new Pair<>(blockPaletteDict.get(blockIdOpt.get()).getLeft(), yOpt.get());
            }
        }

        stepRecLayers = new ArrayList<>();
        stepDomLayers = new ArrayList<>();
        for (int j = 0; j < 64; j++) {
            stepRecLayers.add(new HashMap<>());
            stepDomLayers.add(new HashMap<>());
        }
        Block filler = fillerBlock.get();

        for (int px = 0; px < 128; px++) {
            int pair = px / 2;
            for (int pz = 0; pz < 128; pz++) {
                Pair<Block, Integer> cell = heightMap[px][pz + 1];
                if (cell == null) continue;
                int y = cell.getRight();
                // North neighbour height: a missing column counts as one lower (-> Light)
                int northY = heightMap[px][pz] != null ? heightMap[px][pz].getRight() : y - 1;
                HashMap<BlockPos, Block> layer = ((px + pz) & 1) == 0 ? stepRecLayers.get(pair) : stepDomLayers.get(pair);
                layer.put(new BlockPos(px, 0, pz), cell.getLeft());
                if (y == northY) {
                    // Flat: shade with the north pixel's own colour (the "borrow"); resin when
                    // the north pixel is absent (gap or the noobline row pz = 0)
                    Block northMat = pz >= 1 && heightMap[px][pz] != null ? heightMap[px][pz].getLeft() : filler;
                    layer.put(new BlockPos(px, 0, pz - 1), northMat);
                } else if (y < northY) {
                    // Dark: north is higher - resin on the north column
                    layer.put(new BlockPos(px, 1, pz - 1), filler);
                }
                // Light: no filler
            }
        }

        int recTotal = 0;
        for (HashMap<BlockPos, Block> layer : stepRecLayers) recTotal += layer.size();
        int domTotal = 0;
        for (HashMap<BlockPos, Block> layer : stepDomLayers) domTotal += layer.size();
        info("Step-checker layers: §a64 pairs§7, rec §a" + recTotal + "§7, dom §a" + domTotal
            + "§7, grand §a" + (recTotal + domTotal) + "§7 blocks");
    }

    // --- Phase 1: batch assembly (the placement-plan source for the slave) ---

    /**
     * World-coordinate placement plan for one regular batch k (0..62), per the authority
     * doc §4: recessive pair k at corner+0 (y0-1) + dominant pair k at corner+2 (y2-3) +
     * recessive pair k+1 at corner+2 (east lanes) - the 4x128x4 window over lanes 2k..2k+3,
     * noobline fillers included (converter local z = -1 row). Overlapping entries between
     * the two +2 layers cannot happen (different parity lanes).
     */
    public HashMap<BlockPos, Block> assembleBatch(int k) {
        HashMap<BlockPos, Block> batch = new HashMap<>();
        if (k < 0 || k > 62 || stepRecLayers == null) return batch;
        addLayerAt(batch, stepRecLayers.get(k), 0);
        addLayerAt(batch, stepDomLayers.get(k), 2);
        addLayerAt(batch, stepRecLayers.get(k + 1), 2);
        return batch;
    }

    /** Priming build (doc §4): recessive-only - rec pair 0 at corner+0 + rec pair 1 at corner+2, NO dominants. */
    public HashMap<BlockPos, Block> assemblePriming() {
        HashMap<BlockPos, Block> batch = new HashMap<>();
        if (stepRecLayers == null) return batch;
        addLayerAt(batch, stepRecLayers.get(0), 0);
        addLayerAt(batch, stepRecLayers.get(1), 2);
        return batch;
    }

    /** Closing build (doc §4): dominant pair 63 at corner+2 (lanes 126-127) - built after batch 62, swept from x=253. */
    public HashMap<BlockPos, Block> assembleClosing() {
        HashMap<BlockPos, Block> batch = new HashMap<>();
        if (stepDomLayers == null) return batch;
        addLayerAt(batch, stepDomLayers.get(63), 2);
        return batch;
    }

    /** Maps one converted layer (local x 0..127, y 0/1, z -1..127 with z=-1 = noobline) into the
     *  world at the given y base; later puts win (idempotent re-placement is safe - same block). */
    private void addLayerAt(HashMap<BlockPos, Block> batch, HashMap<BlockPos, Block> layer, int yBase) {
        if (layer == null || lowerMapCorner == null) return;
        for (Map.Entry<BlockPos, Block> entry : layer.entrySet()) {
            BlockPos local = entry.getKey();
            batch.put(new BlockPos(
                lowerMapCorner.getX() + local.getX(),
                lowerMapCorner.getY() + yBase + local.getY(),
                lowerMapCorner.getZ() + local.getZ()), entry.getValue());
        }
    }

    /**
     * Phase-1 self-check: per-batch plan sizes (debug) + summary with print-box containment.
     * The printed numbers are compared offline against the python oracle (convert_step.py +
     * the same y-offset assembly); block-level world equality is verified by the placement
     * engine's gate once it lands.
     */
    private void verifyBatchAssembly() {
        if (stepRecLayers == null || lowerMapCorner == null) return;
        boolean inBox = true;
        int total = 0;
        for (int k = 0; k <= 62; k++) {
            HashMap<BlockPos, Block> batch = assembleBatch(k);
            total += batch.size();
            for (BlockPos pos : batch.keySet()) {
                if (pos.getX() < lowerMapCorner.getX() || pos.getX() > lowerMapCorner.getX() + 127
                    || pos.getY() < lowerMapCorner.getY() || pos.getY() > lowerMapCorner.getY() + 3
                    || pos.getZ() < lowerMapCorner.getZ() - 1 || pos.getZ() > lowerMapCorner.getZ() + 127) {
                    inBox = false;
                    if (debugPrints.get()) warning("Batch assembly out of print box: " + pos.toShortString());
                }
            }
            if (debugPrints.get()) info("batch " + k + " plan: " + batch.size() + " blocks");
        }
        info("Batch assembly: §a63 batches§7 = §a" + total + "§7 blocks, priming §a" + assemblePriming().size()
            + "§7, closing §a" + assembleClosing().size() + "§7 - bounds " + (inBox ? "§aPASS" : "§cFAIL"));
    }

    // Rendering

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WTable table = new WTable();
        list.add(table);

        File configFolder = new File(mapFolder, "_configs");
        if (!configFolder.exists()) return table;

        table.add(theme.label("Configurations: "));
        // ---- Save config button ----
        WButton saveButton = table.add(theme.button("Save Config")).widget();
        saveButton.action = () -> {
            String path = TinyFileDialogs.tinyfd_saveFileDialog(
                "Save Config",
                new File(configFolder, "step-checkered-config.json").getAbsolutePath(),
                null,
                null
            );
            if (path != null) saveConfig(new File(path));
        };

        // ---- Load config button ----
        WButton loadButton = table.add(theme.button("Load Config")).widget();
        loadButton.action = () -> {
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                "Load Config",
                new File(configFolder, "step-checkered-config.json").getAbsolutePath(),
                null,
                null,
                false
            );
            if (path != null) loadConfig(new File(path));
        };
        table.row();

        // ---- START ALL button: starts the run on BOTH bots simultaneously ----
        // The master begins its lifecycle (map fill + checkpoint walks) and the slave
        // enters standby ready for the master's TCP commands. Both modules must be
        // enabled and the slave must be registered via TCP.
        WButton startAllButton = table.add(theme.button("§aSTART ALL")).widget();
        startAllButton.action = () -> {
            info("§aSTART ALL pressed.");
            if (!isActive()) {
                info("§7Enabling the module first...");
                toggle();
            }
            if (SlaveSystem.isSlave) {
                info("§7This is the slave instance - the master's START ALL controls the run.");
                return;
            }
            if (SlaveSystem.slaves.isEmpty()) {
                warning("No slave connected. Enable the module on the slave instance first.");
                return;
            }
            // Give the module one tick to finish activation, then start the run
            startBuildingRequested = true;
        };
        table.row();

        WButton closeConnection = table.add(theme.button("Close Connection")).widget();
        closeConnection.action = () -> {
            SlaveSystem.closeConnection();
            if (isActive()) toggle();
        };
        table.row();

        // ---- RESET button: abort slave, clear progress, fresh run from batch 0 ----
        WButton resetButton = table.add(theme.button("§cRESET RUN")).widget();
        resetButton.action = () -> {
            info("§cRESET button pressed.");
            resetStepRun();
        };
        table.row();

        WTable slaveTable = new WTable();
        list.add(slaveTable);

        SlaveTableController slaveController = new SlaveTableController(slaveTable, theme, true);
        slaveController.rebuild();

        SlaveSystem.tableController = slaveController;
        return list;
    }

    @Override
    public String getInfoString() {
        if (mapFile != null) {
            return mapFile.getName();
        } else {
            return "None";
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (lowerMapCorner == null || !render.get()) return;
        event.renderer.box(lowerMapCorner.getX(), lowerMapCorner.getY(), lowerMapCorner.getZ(), lowerMapCorner.getX() + upperMapLayer.length, lowerMapCorner.getY(), lowerMapCorner.getZ() + upperMapLayer[0].length, color.get(), color.get(), ShapeMode.Lines, 0);
        if (upperMapCorner == null) return;
        event.renderer.box(upperMapCorner.getX(), upperMapCorner.getY(), upperMapCorner.getZ(), upperMapCorner.getX() + upperMapLayer.length, upperMapCorner.getY(), upperMapCorner.getZ() + upperMapLayer[0].length, color.get(), color.get(), ShapeMode.Lines, 0);

        if (renderNextBlockPos.get() && nextBlockPos != null) {
            event.renderer.box(nextBlockPos, color.get(), color.get(), ShapeMode.Lines, 0);
        }

        if (renderMap.get() && lowerMapCorner != null && stepRecLayers != null) {
            // The batch placement plan (assembleBatch) is the render source; cache it and only
            // rebuild when the batch index, art file or corner changes
            String planKey = effectiveRenderBatch() + "|" + (mapFile == null ? "?" : mapFile.getName())
                + "|" + lowerMapCorner.getX() + "," + lowerMapCorner.getY() + "," + lowerMapCorner.getZ()
                + "|" + currentBatch;   // currentBatch in the key so the plan shifts when the batch advances
            if (!planKey.equals(renderedBatchKey)) {
                renderedBatchKey = planKey;
                renderedBatchPlan = assembleBatch(effectiveRenderBatch());
            }
            if (renderedBatchPlan != null) {
                for (Map.Entry<BlockPos, Block> entry : renderedBatchPlan.entrySet()) {
                    if (!miningBlockState(entry.getKey()).isAir()) continue;   // already built
                    Color renderColor = color.get();
                    if (entry.getValue().equals(fillerBlock.get())) renderColor = Color.BLUE;
                    event.renderer.box(entry.getKey(), renderColor, renderColor, ShapeMode.Lines, 0);
                }
            }
        }

        if (renderCheckpointGrid.get() && lowerMapCorner != null) {
            // The full 325-platform master grid; the rendered batch's sweep lane (x=2k+127)
            // is highlighted so the next 5 checkpoints stand out from the rest
            int[] spotZ = {14, 40, 64, 88, 112};
            int nextLaneX = 2 * effectiveRenderBatch() + 127;
            for (int j = 0; j <= 64; j++) {
                int x = 125 + 2 * j;
                boolean isNextLane = x == nextLaneX;
                for (int z : spotZ) {
                    BlockPos platform = new BlockPos(lowerMapCorner.getX() + x, lowerMapCorner.getY() - 1, lowerMapCorner.getZ() + z);
                    Color gridColor = isNextLane ? nextCheckpointsColor.get() : color.get();
                    event.renderer.box(platform, gridColor, gridColor, isNextLane ? ShapeMode.Both : ShapeMode.Lines, 0);
                }
            }
            // Special master points, distinct from the lane grid: the map fill spot (entry)
            // and the cartography lock position captured at registration
            Vec3d[] specialPoints = cartographyTable != null
                ? new Vec3d[]{
                    new Vec3d(lowerMapCorner.getX() + 125.5, lowerMapCorner.getY(), lowerMapCorner.getZ() + 115.5),
                    cartographyTable.getRight()}
                : new Vec3d[]{new Vec3d(lowerMapCorner.getX() + 125.5, lowerMapCorner.getY(), lowerMapCorner.getZ() + 115.5)};
            for (Vec3d point : specialPoints) {
                double s = indicatorSize.get() + 0.15;
                event.renderer.box(point.x - s, point.y - s, point.z - s, point.x + s, point.y + s, point.z + s,
                    specialCheckpointsColor.get(), specialCheckpointsColor.get(), ShapeMode.Both, 0);
            }
        }

        ArrayList<Pair<BlockPos, Vec3d>> renderedPairs = new ArrayList<>();
        for (ArrayList<Pair<BlockPos, Vec3d>> list : materialDict.values()) {
            renderedPairs.addAll(list);
        }
        renderedPairs.addAll(mapMaterialChests);
        for (Pair<BlockPos, Vec3d> pair : renderedPairs) {
            if (renderChestPositions.get())
                event.renderer.box(pair.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
            if (renderOpenPositions.get()) {
                Vec3d openPos = pair.getRight();
                event.renderer.box(openPos.x - indicatorSize.get(), openPos.y - indicatorSize.get(), openPos.z - indicatorSize.get(), openPos.x + indicatorSize.get(), openPos.y + indicatorSize.get(), openPos.z + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
        }

        if (renderCheckpoints.get()) {
            for (Pair<Vec3d, Pair<String, BlockPos>> pair : checkpoints) {
                Vec3d cp = pair.getLeft();
                event.renderer.box(cp.x - indicatorSize.get(), cp.y - indicatorSize.get(), cp.z - indicatorSize.get(), cp.x + indicatorSize.get(), cp.y + indicatorSize.get(), cp.z + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
        }

        if (renderSpecialInteractions.get()) {
            if (cartographyTable != null) {
                event.renderer.box(cartographyTable.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
                event.renderer.box(cartographyTable.getRight().x - indicatorSize.get(), cartographyTable.getRight().y - indicatorSize.get(), cartographyTable.getRight().z - indicatorSize.get(), cartographyTable.getRight().x + indicatorSize.get(), cartographyTable.getRight().y + indicatorSize.get(), cartographyTable.getRight().z + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
            if (finishedMapChest != null) {
                event.renderer.box(finishedMapChest.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
                event.renderer.box(finishedMapChest.getRight().x - indicatorSize.get(), finishedMapChest.getRight().y - indicatorSize.get(), finishedMapChest.getRight().z - indicatorSize.get(), finishedMapChest.getRight().getX() + indicatorSize.get(), finishedMapChest.getRight().getY() + indicatorSize.get(), finishedMapChest.getRight().getZ() + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
            for (Pair<BlockPos, Vec3d> pair : Arrays.asList(anvil, foodChest, xpBottleChest)) {
                if (pair == null) continue;
                event.renderer.box(pair.getLeft(), color.get(), color.get(), ShapeMode.Lines, 0);
                event.renderer.box(pair.getRight().x - indicatorSize.get(), pair.getRight().y - indicatorSize.get(), pair.getRight().z - indicatorSize.get(), pair.getRight().x + indicatorSize.get(), pair.getRight().y + indicatorSize.get(), pair.getRight().z + indicatorSize.get(), color.get(), color.get(), ShapeMode.Both, 0);
            }
        }
    }

    private void forEachMapBlock(MapBlockAction action) {
        forEachMapBlock(action, false);
    }

    private void forEachMapBlock(MapBlockAction action, boolean completeMap) {
        int leftX = completeMap ? 0 : workingInterval.getLeft();
        int rightX = completeMap ? lowerMapLayer.length-1 : workingInterval.getRight();
        for (int x = leftX; x <= rightX; x += linesPerRun) {
            for (int z = 0; z < lowerMapLayer[0].length; z++) {
                for (int lineBonus = 0; lineBonus < linesPerRun; lineBonus++) {
                    int adjustedX = x + lineBonus;
                    if (!Utils.isInInterval(new Pair<>(leftX, rightX), adjustedX)) break;

                    for (int y = 0; y < lowerMapLayer[adjustedX][z].length; y++) {
                        Block block = getActiveMapLayer()[adjustedX][z][y];
                        BlockPos pos = getActiveMapCorner().add(adjustedX, y, z);
                        BlockState state = MapAreaCache.getCachedBlockState(pos);

                        if (!action.accept(adjustedX, z, y, state, block)) {
                            // Stop future iterations if false is returned
                            return;
                        }
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface MapBlockAction {
        boolean accept(int x, int z, int y,BlockState state, Block desiredBlock);
    }

    private enum State {
        SelectingLowerMapArea,
        SelectingTable,
        SelectingFinishedMapChest,
        SelectingAnvil,
        SelectingFoodChest,
        SelectingXpChest,
        SelectingChests,
        AwaitRegisterResponse,
        AwaitRestockResponse,
        AwaitMapChestResponse,
        AwaitFinishedMapChestResponse,
        AwaitFoodChestResponse,
        AwaitDepositResponse,
        AwaitBreakPermission2,
        AwaitBreakPermissionAll,
        MasterWaitBuild,
        MasterDwell,
        AwaitClosingBreak,
        AwaitCartographyResponse,
        AwaitNBTFile,
        AwaitBlockBreak,
        AwaitPlacedSuppression,
        AwaitMinedSuppression,
        AwaitMasterAllCleared,
        AwaitSlaveContinue,
        StandBy,
        Walking,
        Mining,
    }

    /**
     * The master-side step-checker scheduler is deliberately separate from the legacy printer
     * states above.  The legacy states still drive registration, restocking, and the original
     * one-bot engine; this state machine owns only the measured two-bot W->E lifecycle.
     */
    private enum StepMasterStage {
        IDLE,
        FETCH_MAP,
        FILL_MAP,
        WAIT_FILLED_MAP,
        WAIT_PRIMING_BUILD,
        PRIMING_WALK,
        WAIT_BATCH_BUILD,
        BATCH_WALK_A,
        BATCH_WALK_B,
        WAIT_BREAK_2,
        WAIT_BREAK_ALL,
        WAIT_CLOSING_BUILD,
        CLOSING_WALK,
        WAIT_CLOSING_BREAK,
        LOCK_WALK,
        LOCKING,
        READY_TO_LOCK,
        ABORTED
    }

    private enum SprintMode {
        Off,
        NotPlacing,
        Always
    }

    private enum ChestRole {
        Material,
        Food,
        Xp
    }
}

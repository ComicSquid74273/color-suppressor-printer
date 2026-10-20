package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.Addon;
import com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager;
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
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.speed.Speed;
import meteordevelopment.meteorclient.systems.modules.movement.speed.SpeedModes;
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
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.CartographyTableScreenHandler;
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
import java.util.function.Predicate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class StepCheckered extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMining = settings.createGroup("Mining & Collecting", false);
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced", false);
    private final SettingGroup sgMultiUser = settings.createGroup("Multi User", false);
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    // Legacy (parked) engine line width - fixed at 2, not a setting anymore
    private static final int linesPerRun = 2;

    final Setting<Double> interactionRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("interaction-range")
        .description("The maximum range you can place blocks around yourself.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );

    final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place per game tick (default 4, some servers allow more).")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    final Setting<Integer> breaksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("breaks-per-tick")
        .description("How many blocks to break per game tick (default 4, some servers allow more).")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    final Setting<List<Block>> startBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("start-blocks")
        .description("Which block to interact with to start the printing process.")
        .defaultValue(Blocks.STONE_BUTTON, Blocks.ACACIA_BUTTON, Blocks.BAMBOO_BUTTON, Blocks.BIRCH_BUTTON,
            Blocks.CRIMSON_BUTTON, Blocks.DARK_OAK_BUTTON, Blocks.JUNGLE_BUTTON, Blocks.OAK_BUTTON,
            Blocks.POLISHED_BLACKSTONE_BUTTON, Blocks.SPRUCE_BUTTON, Blocks.WARPED_BUTTON)
        .build()
    );

    final Setting<Block> fillerBlock = sgGeneral.add(new BlockSetting.Builder()
        .name("filler-block")
        .description("The block to use as a filler.")
        .defaultValue(Blocks.RESIN_BLOCK)
        .build()
    );


    final Setting<SprintMode> sprinting = sgGeneral.add(new EnumSetting.Builder<SprintMode>()
        .name("sprint-mode")
        .description("How to sprint.")
        .defaultValue(SprintMode.Always)
        .build()
    );

    final Setting<Boolean> activationReset = sgGeneral.add(new BoolSetting.Builder()
        .name("activation-reset")
        .description("Disable if the bot should continue after reconnecting to the server.")
        .defaultValue(true)
        .build()
    );

    // rotate-place setting removed: 6b6t anticheat is soft, allows placing/breaking
    // without rotation. All placement and breaking goes forward without rotating.

    final Setting<Boolean> customFolderPath = sgGeneral.add(new BoolSetting.Builder()
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

    final Setting<Boolean> useDefaultConfigFile = sgGeneral.add(new BoolSetting.Builder()
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

    final Setting<Integer> minDurability = sgMining.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Remaining durability tools are exchanged at once they have gone BELOW it. The "
            + "exchange happens ONLY at the next batch-prep restock trip (old tool deposited into "
            + "its chest, fresh one taken) - never mid-run, where a worn tool keeps working. Below "
            + "~800 (one batch's worst-case breaking) a pickaxe can reach 0 and break mid-batch; "
            + "set it to ~800 or more to guarantee tools never break.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 2031)
        .build()
    );

    // Advanced

    final Setting<Integer> preRestockDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("pre-restock-delay")
        .description("How many ticks to wait to take items after opening the chest.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    final Setting<Integer> invActionDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("inventory-action-delay")
        .description("How many ticks to wait between each inventory action (moving a stack).")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    final Setting<Integer> postRestockDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("post-restock-delay")
        .description("How many ticks to wait after restocking.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    final Setting<Integer> preSwapDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("pre-swap-delay")
        .description("How many ticks to wait before swapping an item into the hotbar.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    final Setting<Integer> postSwapDelay = sgAdvanced.add(new IntSetting.Builder()
        .name("post-swap-delay")
        .description("How many ticks to wait after swapping an item into the hotbar.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    final Setting<Integer> retryInteractTimer = sgAdvanced.add(new IntSetting.Builder()
        .name("retry-interact-timer")
        .description("How many ticks to wait for chest response before interacting with it again.")
        .defaultValue(80)
        .min(1)
        .sliderRange(20, 200)
        .build()
    );

    final Setting<Integer> posResetTimeout = sgAdvanced.add(new IntSetting.Builder()
        .name("pos-reset-timeout")
        .description("How many ticks to wait after the player position was reset by the server.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 40)
        .build()
    );

    final Setting<Double> checkpointBuffer = sgAdvanced.add(new DoubleSetting.Builder()
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

    final Setting<Boolean> snapToCheckpoints = sgAdvanced.add(new BoolSetting.Builder()
        .name("snap-to-checkpoints")
        .description("Snap to checkpoints when getting close.")
        .defaultValue(false)
        .build()
    );

    final Setting<Boolean> moveToFinishedFolder = sgAdvanced.add(new BoolSetting.Builder()
        .name("move-to-finished-folder")
        .description("Moves finished NBT files into the finished-maps folder in the color-suppressor folder.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> disableOnFinished = sgAdvanced.add(new BoolSetting.Builder()
        .name("disable-on-finished")
        .description("Disables the printer when all nbt files are finished.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> debugPrints = sgAdvanced.add(new BoolSetting.Builder()
        .name("debug-prints")
        .description("Prints additional information.")
        .defaultValue(false)
        .build()
    );

    final Setting<Boolean> batch0SplitBuild = sgAdvanced.add(new BoolSetting.Builder()
        .name("batch0-split-build")
        .description("Split batch 0 into floor/tops halves around W1 (the proven fix for pair 0's floor recording). OFF = batch 0 runs the STANDARD flow exactly like every other batch: build(4) -> W1 -> break(2) -> W2 -> break-all, no pre-build phase.")
        .defaultValue(false)
        .build()
    );

    final Setting<Integer> startBatch = sgAdvanced.add(new IntSetting.Builder()
        .name("start-batch")
        .description("First batch to build in a fresh run - all batches before it are SKIPPED (nothing built, no walks). 0 = start at batch 0; set 1 to test the pipeline from batch 1 onward.")
        .defaultValue(1)
        .sliderRange(0, 62)
        .build()
    );

    // Multi User

    final Setting<String> tcpIp = sgMultiUser.add(new StringSetting.Builder()
        .name("TCP-IP")
        .description("The loopback IP used by bot-to-bot TCP communication.")
        .defaultValue("127.0.0.1")
        .onChanged(value -> updateTcpAddress())
        .build()
    );

    final Setting<Integer> tcpPort = sgMultiUser.add(new IntSetting.Builder()
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

    final Setting<BotRole> botRole = sgMultiUser.add(new EnumSetting.Builder<BotRole>()
        .name("bot-role")
        .description("master: this bot hosts the TCP server and walks the checkpoints. slave: this bot builds/breaks. Must be set on both instances before starting.")
        .defaultValue(BotRole.empty)
        .build()
    );

    final Setting<String> masterUsername = sgMultiUser.add(new StringSetting.Builder()
        .name("master-username")
        .description("Username of the account that acts as MASTER. Each bot self-identifies by its username - set this on both instances. Overrides bot-role.")
        .defaultValue("")
        .onChanged(value -> updateTcpAddress())
        .build()
    );

    final Setting<String> slaveUsername = sgMultiUser.add(new StringSetting.Builder()
        .name("slave-username")
        .description("Username of the account that acts as SLAVE. Each bot self-identifies by its username - set this on both instances. Overrides bot-role.")
        .defaultValue("")
        .onChanged(value -> updateTcpAddress())
        .build()
    );

    //Render

    final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the selected areas.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> renderMap = sgRender.add(new BoolSetting.Builder()
        .name("render-map")
        .description("Highlights the placement plan of the selected batch (what the slave will build).")
        .defaultValue(false)
        .visible(() -> render.get())
        .build()
    );

    final Setting<Integer> renderBatch = sgRender.add(new IntSetting.Builder()
        .name("render-batch")
        .description("The batch index (0-62) whose placement plan render-map highlights. Follows the engine's current batch once runs start.")
        .defaultValue(0)
        .min(0)
        .max(62)
        .sliderRange(0, 62)
        .visible(() -> render.get() && renderMap.get())
        .build()
    );

    final Setting<Boolean> renderNextBlockPos = sgRender.add(new BoolSetting.Builder()
        .name("render-next-block-pos")
        .description("Highlights the block position of the current place/mining action.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    final Setting<Boolean> renderChestPositions = sgRender.add(new BoolSetting.Builder()
        .name("render-chest-positions")
        .description("Highlights the selected chests.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    final Setting<Boolean> renderOpenPositions = sgRender.add(new BoolSetting.Builder()
        .name("render-open-positions")
        .description("Indicate the position the bot will go to in order to interact with the chest.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    final Setting<Boolean> renderCheckpoints = sgRender.add(new BoolSetting.Builder()
        .name("render-checkpoints")
        .description("Indicate the checkpoints the bot will traverse.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    final Setting<Boolean> renderCheckpointGrid = sgRender.add(new BoolSetting.Builder()
        .name("render-checkpoint-grid")
        .description("Highlights ALL 325 master platform checkpoints (x=125+2j, z=14/40/64/88/112). The 5 checkpoints of the rendered batch's sweep lane use the next-checkpoints color; the fill spot and cartography lock position use the special-checkpoints color.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    final Setting<Boolean> renderMasterArea = sgRender.add(new BoolSetting.Builder()
        .name("render-master-area")
        .description("Draws the MASTER AREA bounding box (x 124..255, z 12..117 around the platform lattice) and the single ENTRY/EXIT GATE (north-west platform 125,14) in the special-checkpoints color. Inside the box the master moves only along lattice lines; it enters/exits only through the gate.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    final Setting<SettingColor> nextCheckpointsColor = sgRender.add(new ColorSetting.Builder()
        .name("next-checkpoints-color")
        .description("Color for the 5 checkpoints of the rendered batch's sweep lane (follows render-batch).")
        .defaultValue(new SettingColor(255, 165, 0, 190))
        .visible(() -> render.get() && renderCheckpointGrid.get())
        .build()
    );

    final Setting<SettingColor> specialCheckpointsColor = sgRender.add(new ColorSetting.Builder()
        .name("special-checkpoints-color")
        .description("Color for the master's special points: the map fill spot (entry) and the cartography lock position.")
        .defaultValue(new SettingColor(255, 70, 70, 190))
        .visible(() -> render.get() && renderCheckpointGrid.get())
        .build()
    );

    final Setting<Boolean> renderSpecialInteractions = sgRender.add(new BoolSetting.Builder()
        .name("render-special-interactions")
        .description("Indicate the position where the reset button and cartography table will be used.")
        .defaultValue(true)
        .visible(() -> render.get())
        .build()
    );

    final Setting<Double> indicatorSize = sgRender.add(new DoubleSetting.Builder()
        .name("indicator-size")
        .description("How big the rendered indicator will be.")
        .defaultValue(0.13)
        .min(0)
        .sliderRange(0, 1)
        .visible(() -> render.get())
        .build()
    );

    final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("The render color.")
        .defaultValue(new SettingColor(22, 230, 206, 155))
        .visible(() -> render.get())
        .build()
    );

    int timeoutTicks;
    int interactTimeout;
    int toBeSwappedSlot;
    int heartbeatTicks;
    boolean pendingDisable;                                          // deferred toggle (see requestDisable)
    StepCheckerLayers stepLayers;                                    // converted per-pair rec/dom layers (StepCheckerLayers.empty() until the NBT loads)
    int currentBatch = -1;                                           // batch being built (slave)
    int batchPassRetries;
    int breakStage;                                                  // 0 = printing, 2 = break(2), 4 = break-all
    boolean printRepairPending;                                      // print pass ended with missing blocks
    int sweepBudget;                                                 // tick budget for the straggler sweep
    volatile boolean startBuildingRequested;                         // START ALL deferred trigger
    boolean fullSweepActive;                                         // full-area RESET sweep in progress
    // RESET ladder (checkpoint batches): 32 four-lane columns x 5 z-segments; each
    // segment is broken + picked to 100% and CONFIRMED by a bounded scan before the
    // ladder advances. Columns snake (N->S, S->N) so station moves stay short.
    int resetLadderCol;                                              // 0..31 current column (32 = verify phase)
    int resetLadderSeg;                                              // 0..4  current z-segment (resume anchor)
    int resetLadderEndCol;                                           // last column of the scoped reset
    int restockCooldown;                                             // blocks repeat supply-run triggers
    HashMap<BlockPos, Integer> placementCooldowns;                   // litematica-style: retry timer per position
    HashMap<BlockPos, Integer> placementAttempts;                    // per-position placement attempts (adaptive-pace cap)
    int placeHoldTicks;                                              // consecutive adaptive-pace hold ticks (watchdog)
    BlockPos vanillaStickyTarget;                                    // ONE multi-tick vanilla target at a time (progress must accumulate)
    int rateTick;                                                   // 20-tick rate-report cycle
    int breakSentPerSec, breakConfirmedPerSec, placeSentPerSec;      // [RATE] verification counters
    HashMap<BlockPos, Integer> collectSkips;               // collection run: picks per drop (10 = blacklisted)
    // BREAK CONFIRMATION: fastBreak sends packets but the server's block-update
    // arrives 1-2 ticks later. These track recently-broken positions and retry
    // any that the server didn't process — BEFORE the bot walks past them.
    HashMap<BlockPos, Integer> breakPending;                        // pos -> ticks until silently confirmed
    HashSet<BlockPos> needsVanillaBreak;                  // fastBreak failed → use vanilla mining
    HashMap<BlockPos, Integer> vanillaBreakRetries;                 // pos -> times expired into vanilla fallback (server-rejection evidence)
    BlockPos jamWatchTarget;                                        // feed-jam detector: armed target held out of range
    int jamWatchTicks;
    boolean breakSweepActedThisTick;                                // the Mining-state break loop already sent packets this tick
    int resetPendingScope = -2;                                     // persisted RESET scope (-2 unread, -1 none, >=0 resume)                                     // in-memory mirror of the progress file's batch (idle render + resume UI)
    String resetStallSig;                                           // reset arrival stall detector: last arrival state signature
    int resetStallRounds;
    int sweepWaitRounds;                                            // batch sweep: consecutive in-flight-drop dwell arrivals
    boolean sweepWaitLogged;                                        // dwell entry already logged
    String sweepReArmSig;                                           // batch sweep: state signature at the previous finishBatch re-arm
    String lastCheckpointAction;
    int stuckCheckpointCount;                                         // forward-hold ticks for item pickup
    volatile boolean break2Permitted;                                // master calls (TCP "step:break2")
    volatile boolean breakAllPermitted;                              // master calls (TCP "step:breakall")
    HashMap<BlockPos, Block> currentBuildPlan;                       // the slave's active placement plan
    boolean closeNextInvPacket;
    State state;
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
    ArrayList<Triple<Item, Integer, Integer>> restockList;          // Material, Stacks, Raw Amount
    ArrayList<BlockPos> checkedChests;
    ArrayList<Pair<Vec3d, Pair<String, BlockPos>>> checkpoints;     // (GoalPos, (checkpointAction, targetBlock))
    ArrayList<File> startedFiles;
    ArrayList<Integer> restockBacklogSlots;
    ArrayList<Integer> depositBacklogSlots;
    HashSet<BlockPos> fullDepositChests;                             // chests that ran out of room this batch
    private final ArrayDeque<String> pendingStepCommands = new ArrayDeque<>();
    File mapFolder;
    File mapFile;
    BlockPos lowerMapCorner;
    BlockPos upperMapCorner;

    /** RESET ladder z-segments: inclusive row ranges and station centres (relative). */
    private static final int[] RESET_SEG_START = {0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120};
    private static final int[] RESET_SEG_END = {7, 15, 23, 31, 39, 47, 55, 63, 71, 79, 87, 95, 103, 111, 119, 127};
    private static final int[] RESET_SEG_CENTER_Z = {4, 12, 20, 28, 36, 44, 52, 60, 68, 76, 84, 92, 100, 108, 116, 124};
    /** ITEM SCAN MARGIN: block drops skid up to 3-4 blocks outside the segment /
     *  print box in any horizontal direction (worst case, user-measured 2026-10-20).
     *  Item scans (arrival, verify walk, batch gates) extend this far past the block
     *  bounds so outside drops are chased and collected, not ignored. */
    private static final int RESET_ITEM_MARGIN = 4;
    /** Route actions during which placement may happen (also gates the Dynamic speed mode:
     *  these + Mining = "working", everything else is transit). */
    private static final List<String> ALLOW_PLACE_ACTIONS = Arrays.asList("sprint",
        "batchBegin", "printDone", "printDone2",
        "awaitBreak2", "break2Done", "breakAllBegin", "breakAllDone", "stepClosingReady", "stepClosingWait",
        "stepClosingDone", "batchExit", "sweepTick", "fullSweepStart", "fullSweepCenter", "fullSweepTick",
        "fullSweepDone", "resetStation", "resetPass");

    /** MASTER AREA (user rule, 2026-10-20): the platform lattice is a BOUNDED area -
     *  lanes x_rel 125..253 (x = 125+2j), rows z_rel {14,40,64,88,112}, plus the fill
     *  at the gate too. Inside it the master moves ONLY along lattice lines; the area
     *  is entered and exited ONLY through the north-west GATE (platform 125,14) -
     *  never from anywhere else. Violations are logged by the lattice watchdog. */
    static final int MASTER_AREA_X0 = 124, MASTER_AREA_X1 = 255;
    static final int MASTER_AREA_Z0 = 12, MASTER_AREA_Z1 = 114;

    private final StepRenderer renderer = new StepRenderer(this);
    final StepMaster master = new StepMaster(this);

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
        sb.append("\"stepMasterStage\":\"").append(master.stepMasterStage).append("\",");
        sb.append("\"workingMapId\":").append(master.stepWorkingMapId).append(',');
        sb.append("\"batch\":").append(master.stepMasterBatch).append(',');
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
                sb.append(",\"mapPixels\":\"").append(Base64.getEncoder().encodeToString(cols)).append('"');
            }
        }
        String pos = mc != null && mc.player != null
            ? String.format(Locale.ROOT, "\"x\":%.1f,\"y\":%.1f,\"z\":%.1f",
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

    /** Local supervisor hook for the map-hold safety switch (the creative runner's
     *  /holdmap). Served continuously by the tick while no master run is active:
     *  pin unnamed filled map -> keep it held; with none, auto-use ONE empty map at
     *  the current spot and pin the filled result once it lands (the old dry-run). */
    public void controlHoldMap(boolean on) {
        if (SlaveSystem.isSlave) return;
        master.controlHoldActive = on;
        if (!on) master.stepStowMap();
    }

    /**
     * Start the configured cartography-table lock machine after the final map walk.  The
     * working map is selected by pinned map id; named reference maps are never accepted.
     */
    public void requestLockMap() {
        if (SlaveSystem.isSlave || master.stepMasterStage != StepMasterStage.READY_TO_LOCK) {
            warning("Lock is available only after the master reaches READY_TO_LOCK.");
            return;
        }
        if (cartographyTable == null) {
            error("No cartography table is configured for the locked-map end flow.");
            return;
        }
        master.stepLockStep = 0;
        master.resetForActivation();
        master.stepMasterStage = StepMasterStage.LOCK_WALK;
        checkpoints.clear();
        checkpoints.add(new Pair<>(cartographyTable.getRight(), new Pair<>("stepLockTable", null)));
        master.prependLatticeTransit(lowerMapCorner.getX() + 125.5, lowerMapCorner.getZ() + 14.5);   // exit via the GATE
        state = State.Walking;
        master.stepStowMap();
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
                if ("close".equals(parts[1])) {
                    master.stepSlaveReadyClosing = true;
                } else {
                    Integer batch = parseStepBatch(parts[1]);
                    if (batch != null) master.stepSlaveReadyBatch = batch;
                }
            }
            case "break2done" -> {
                Integer batch = parseStepBatch(parts.length > 1 ? parts[1] : null);
                if (batch != null) master.stepSlaveBreak2DoneBatch = batch;
            }
            case "done" -> {
                Integer batch = parseStepBatch(parts.length > 1 ? parts[1] : null);
                if (batch != null) master.stepSlaveDoneBatch = batch;
            }
            case "closed" -> master.stepSlaveClosingDone = true;
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
        fullSweepActive = false;   // a module re-toggle must never inherit a dead reset
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
        timeoutTicks = 0;
        interactTimeout = 0;
        toBeSwappedSlot = -1;
        heartbeatTicks = 0;
        pendingDisable = false;
        placementCooldowns = new HashMap<>();
        placementAttempts = new HashMap<>();
        collectSkips = new HashMap<>();
        placeHoldTicks = 0;
        breakPending = new HashMap<>();
        needsVanillaBreak = new HashSet<>();
        vanillaBreakRetries = new HashMap<>();
        jamWatchTarget = null;
        jamWatchTicks = 0;
        breakSweepActedThisTick = false;
        resetStallSig = null;
        resetStallRounds = 0;
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        sweepReArmSig = null;
        synchronized (pendingStepCommands) {
            pendingStepCommands.clear();
        }
        master.resetForActivation();
        currentBatch = -1;
        currentBuildPlan = null;
        breakStage = 0;
        printRepairPending = false;
        break2Permitted = false;
        breakAllPermitted = false;
        stepLayers = StepCheckerLayers.empty();   // placeholder until the first NBT load
        debugPreviousState = null;

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
        master.cachedResumeBatch = master.loadStepProgress();   // idle render + resume show where the run actually is
        resetPendingScope = loadResetPending();

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
        setTransitSpeed(false);
        if (!SlaveSystem.isSlave && master.stepMasterStage != StepMasterStage.IDLE) {
            master.stepStowMap();
            master.stepMasterStage = StepMasterStage.ABORTED;
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
                if (mc.world.getBlockState(blockPos).getBlock().equals(Blocks.CARTOGRAPHY_TABLE)) {
                    cartographyTable = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("§aCartography Table selected. Select the §bFinished Map Chest.");
                    state = State.SelectingFinishedMapChest;
                }
                break;
            case SelectingFinishedMapChest:
                blockPos = packet.getBlockHitResult().getBlockPos();
                if (mc.world.getBlockState(blockPos).getBlock() instanceof AbstractChestBlock) {
                    finishedMapChest = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("§aFinished Map Chest selected. Select the §bAnvil.");
                    state = State.SelectingAnvil;
                }
                break;
            case SelectingAnvil:
                blockPos = packet.getBlockHitResult().getBlockPos();
                if (mc.world.getBlockState(blockPos).getBlock() instanceof AnvilBlock) {
                    anvil = new Pair<>(blockPos, mc.player.getEntityPos());
                    info("§aAnvil selected. Select the §bFood Chest.");
                    state = State.SelectingFoodChest;
                }
                break;
            case SelectingFoodChest:
            case SelectingXpChest:
                blockPos = packet.getBlockHitResult().getBlockPos();
                if (mc.world.getBlockState(blockPos).getBlock().equals(Blocks.CHEST)) {
                    tempChestPos = blockPos;
                    pendingRole = state == State.SelectingFoodChest ? ChestRole.Food : ChestRole.Xp;
                    state = State.AwaitRegisterResponse;
                }
                break;
            case SelectingChests:
                if (startBlocks.get().isEmpty())
                    warning("No block selected as Start Block! Please select one in the settings.");
                blockPos = packet.getBlockHitResult().getBlockPos();
                BlockState blockState = mc.world.getBlockState(blockPos);
                if (mc.world.getBlockState(blockPos).getBlock().equals(Blocks.CHEST)) {
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
            State.AwaitFoodChestResponse, State.AwaitDepositResponse);
        if (allowedStates.contains(state)) {
            toBeHandledInvPacket = packet;
            timeoutTicks = preRestockDelay.get();
        }
    }

    void handleInventoryPacket(InventoryS2CPacket packet) {
        if (debugPrints.get()) info("Handling InvPacket for: " + state);
        closeNextInvPacket = true;
        switch (state) {
            case AwaitRestockResponse:
                handleRestockResponse(packet);
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
            case AwaitFoodChestResponse:
                interactTimeout = 0;
                timeoutTicks = postRestockDelay.get();
                // Take only what FITS in the offhand (user rule): offhand empty -> one
                // full stack (= capacity); partially filled (<10 trigger) -> HALF a
                // chest stack, which always fits beside the remainder. Never a second
                // full stack on top of an existing one.
                int offFood = offhandFoodCount();
                for (int slot = 0; slot < packet.contents().size() - 36; slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) {
                        if (offFood == 0) {
                            mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        } else {
                            mc.interactionManager.clickSlot(packet.syncId(), slot, 1, SlotActionType.PICKUP, mc.player);
                            mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.PICKUP, mc.player);
                        }
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
                if (depositBacklogSlots.isEmpty()) {
                    // Normal flow: backlog is empty at chest-open - build it from every
                    // carried stack that belongs in this chest.
                    for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                        ItemStack stack = packet.contents().get(slot);
                        if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
                        ArrayList<Pair<BlockPos, Vec3d>> stocking = materialDict.get(stack.getItem());
                        if (stocking == null || !containsChest(stocking, lastInteractedChest)) continue;
                        depositBacklogSlots.add(slot);
                    }
                }
                // else: PRE-FILLED backlog = the unneeded-material dump (depositUnneeded
                // action) - keep exactly those slots, deposit nothing else.
                if (depositBacklogSlots.isEmpty()) {
                    // Nothing belongs in THIS chest: hop to the next correct one or continue
                    Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
                    if (nextDepositChest != null) queueDepositChestAtHead(nextDepositChest);
                    timeoutTicks = postRestockDelay.get();
                    state = State.Walking;
                } else {
                    timeoutTicks = invActionDelay.get();
                }
                break;
        }
    }

    /** Restock chest response: pickaxe parking (own-chest rule), then take stacks of
     *  the head restock entry (shuffled chest order; partial stacks make progress). */
    private void handleRestockResponse(InventoryS2CPacket packet) {
        interactTimeout = 0;
        // TOOL RETURN (user rule 2026-10-20): used pickaxes go back to their OWN
        // chest ONLY at this restock-phase visit, one deposit per sync - never
        // mid-batch, never into a material chest. A pending take is a SWAP: the
        // fetch only queues when the best carried pickaxe is below the buffer, so
        // park ALL of them and take the fresh one below (the old deposit took only
        // below-min-durability ones, letting replaced pickaxes ride along forever).
        // With no take pending, keep exactly ONE pickaxe - the best - and return
        // every spare (a sub-10-durability pickaxe is never the kept one: it is
        // either a spare, or the sole pickaxe, in which case the 800-buffer fetch
        // above has already queued its replacement).
        if (isOpenChestAPickaxeChest()) {
            boolean swapTake = !restockList.isEmpty() && restockList.get(0).getMiddle() > 0
                && new ItemStack(restockList.get(0).getLeft()).isIn(ItemTags.PICKAXES);
            int keepSlot = -1;
            if (!swapTake) {
                // Keep-best scan in screen-handler slot space (last 36 slots are
                // the player inventory) - inventory indices don't map 1:1 here.
                int keepRemaining = -1;
                for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                    ItemStack invStack = packet.contents().get(slot);
                    if (invStack.isIn(ItemTags.PICKAXES) && ToolUtils.getRemainingDamage(invStack) > keepRemaining) {
                        keepSlot = slot;
                        keepRemaining = ToolUtils.getRemainingDamage(invStack);
                    }
                }
            }
            for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                if (slot == keepSlot) continue;
                ItemStack invStack = packet.contents().get(slot);
                if (invStack.isIn(ItemTags.PICKAXES)) {
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
                // Deposit-only pickaxe entry (0 stacks to take): spent once nothing
                // is left to park here. A pending TAKE also reaches middle==0 while
                // its backlog drains - the tick's drain ends that entry itself, so
                // only advance when no backlog is in flight.
                if (restockBacklogSlots.isEmpty()
                    && new ItemStack(restockList.get(0).getLeft()).isIn(ItemTags.PICKAXES)) {
                    endRestocking();
                }
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
                    // RECOVER instead of disable (live 2026-10-20: full inventory
                    // of prior-run drops killed the run here): close the chest and
                    // re-run the prep path - its dump now frees the excess slots.
                    warning("§eInventory full at withdrawal - running the excess dump, then retrying the restock.");
                    if (mc.player.currentScreenHandler != null) mc.player.closeHandledScreen();
                    restockBacklogSlots.clear();
                    timeoutTicks = postRestockDelay.get();
                    pauseForRestockTrip(false);
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
    }


    @EventHandler
    private void onTick(TickEvent.Pre event) {
        master.serviceControlHold();
        // START ALL deferred trigger + deferred disable both run through the tick:
        // calling toggle() from inside onActivate/onTick handlers makes Meteor re-enter
        // the activation path recursively (StackOverflowError -> client death)
        if (consumeStartBuildingRequest()) return;

        // Supply-run cooldown ticks down; tryPlacingBlock can't re-trigger mid-trip
        if (restockCooldown > 0) restockCooldown--;

        // Placement cooldowns tick down (litematica-style): expired positions become
        // eligible for retry; successfully placed positions are already skipped by the
        // world-state check in getNextBatchPlacementPos
        if (!placementCooldowns.isEmpty()) {
            placementCooldowns.replaceAll((pos, ticks) -> ticks - 1);
            placementCooldowns.values().removeIf(ticks -> ticks <= 0);
        }

        if (consumePendingDisable()) return;

        if (SlaveSystem.isSlave) {
            if (tryResumePendingReset()) return;
            processPendingStepCommands();
        } else if (master.stepMasterStage != StepMasterStage.IDLE) {
            master.tickStepMaster();
            return;
        }

        // Manual break calls remain available for isolated slave diagnostics; the normal run
        // reaches these flags through the step-checker TCP protocol.
        if (armPermittedBreakPhase()) return;

        // Inventory truly out of space mid-break-all (partial-stack headroom included):
        // deposit the building blocks, then resume
        if (breakStage == 4 && state.equals(State.Mining) && freeInventoryCapacity() == 0) {
            pauseBreakAllForDeposit();
            return;
        }

        if (state == null) return;

        logTickDebug();
        sendSlaveHeartbeat();

        tickInteractRetry();

        updateTransitSpeed();

        if (timeoutTicks > 0) {
            if (mc.player.isOnGround()) timeoutTicks--;
            Utils.setForwardPressed(false);
            return;
        }

        if (consumeHotbarSwap()) return;

        if (drainRestockBacklog()) return;

        if (drainDepositBacklog()) return;

        armResetFeedTarget();

        tickBreakConfirmations();

        pumpArmedBreakTarget();

        if (processDeferredInventoryIO()) return;

        if (!(state.equals(State.Walking) || state.equals(State.Mining))) return;
        if (checkpoints.isEmpty()) return;

        // ADAPTIVE PACE GOVERNOR - the ONE place that decides whether the bot may
        // advance while working. Two modes, one contract (see adaptivePaceHold()).
        Utils.setForwardPressed(!adaptivePaceHold());

        Vec3d goal = checkpoints.get(0).getLeft();
        if (PlayerUtils.distanceTo(goal.add(0, mc.player.getY() - goal.y, 0)) < checkpointBuffer.get()) {
            if (handleCheckpointArrival(goal)) return;
            goal = checkpoints.get(0).getLeft();
        }

        String nextAction = checkpoints.get(0).getRight().getLeft();

        steerTowardGoal(goal, nextAction);
        applySprintMode(nextAction);
        if (!ALLOW_PLACE_ACTIONS.contains(nextAction)) return;

        BlockPos tempPos = pickWorkTarget();

        // ROLLING HOTBAR LOOKAHEAD (user rule: "always, for the whole batch") - runs
        // BEFORE the range gate (v2 fix: the first version sat after it and was
        // unreachable exactly when a swap was needed).
        if (state.equals(State.Walking) && breakStage == 0 && currentBuildPlan != null && rateTick == 10) {
            rollingHotbarLookahead();
        }
        // Range gate uses EYE distance - the same metric the target pickers use. A
        // feet-based 3D check here rejects upper-layer blocks (y+2.5 above the feet)
        // that the picker armed: the adaptive print pace then held the bot on a target
        // this gate refused to attempt - the total silent stall of the 2026-10-20
        // 01:01 run (2 PLACE lines at the staging, then nothing for minutes).
        if (tempPos == null || mc.player.getEyePos().distanceTo(tempPos.toCenterPos()) > interactionRange.get()) return;
        nextBlockPos = tempPos;

        actOnWorkTarget();
    }

    // --- Tick pipeline (decomposed from the former ~1280-line monolithic onTick; the
    // order of these stages is load-bearing - see each method's notes) ---

    /** START ALL deferred trigger: fired one tick after the HUD button enabled the
     *  module, so onActivate + config load have completed before startBuilding runs. */
    private boolean consumeStartBuildingRequest() {
        if (!startBuildingRequested) return false;
        startBuildingRequested = false;
        if (isActive() && !SlaveSystem.isSlave && !SlaveSystem.slaves.isEmpty()) {
            startBuilding();
        }
        return true;
    }

    /** Deferred disable: pendingDisable is consumed here so toggle() never re-enters
     *  the activation path recursively. */
    private boolean consumePendingDisable() {
        if (!pendingDisable) return false;
        pendingDisable = false;
        if (isActive()) toggle();
        return true;
    }

    /** RESET RESUME (user rule): a disable/crash mid-RESET must not skip it. Standing
     *  by with a persisted reset scope -> re-arm the scoped ladder. */
    private boolean tryResumePendingReset() {
        if (resetPendingScope < 0 || state != State.StandBy || !checkpoints.isEmpty()
            || lowerMapCorner == null || fullSweepActive) return false;
        int scope = resetPendingScope;
        info("§eRESET pending (scope cols 0-" + scope + ") - resuming the reset after re-enable.");
        resetLadderEndCol = Math.min(31, Math.max(0, scope));
        resetLadderCol = 0;
        resetLadderSeg = mc.player.getBlockZ() - lowerMapCorner.getZ() > 64 ? 4
            : (mc.player.getBlockZ() - lowerMapCorner.getZ() > 26 ? 2 : 0);
        queueResetLadder(resetLadderCol, 0);
        prependNorthCorridor(checkpoints.getFirst().getLeft().x);
        state = State.Mining;
        breakStage = 4;
        return true;
    }

    /** Manual/TCP break-permission gates. break(2) arms the phase and lets the tick
     *  continue; break-all hands over to startBreakAll and ends the tick. */
    private boolean armPermittedBreakPhase() {
        if (state == State.AwaitBreakPermission2 && break2Permitted) {
            break2Permitted = false;
            // PHASE TOOL SLOTS: breaking needs the tools - pickaxe to 8, carried axe
            // to hotbar 7 (hotbar is 0-8; the old 'axe to 9' addressed main
            // inventory and crashed performSwap's setSelectedSlot). Both leave the
            // staging set until the next print pass. Best-first like the pickaxe.
            ensurePickaxeSlot8();
            availableHotBarSlots.remove(Integer.valueOf(8));
            {
                int axeSlot = -1;
                int axeLife = -1;
                for (int as2 = 0; as2 < 36; as2++) {
                    ItemStack a3 = mc.player.getInventory().getStack(as2);
                    if (a3.isEmpty() || !a3.isIn(ItemTags.AXES)) continue;
                    int life = ToolUtils.getRemainingDamage(a3);
                    if (life >= minDurability.get() && life > axeLife) { axeLife = life; axeSlot = as2; }
                }
                if (axeSlot >= 9) {
                    Utils.performSwap(axeSlot, 7);
                    availableHotBarSlots.remove(Integer.valueOf(7));
                } else if (axeSlot >= 0) {
                    // already in the hotbar - reserve it in place so staging never evicts it
                    availableHotBarSlots.remove(Integer.valueOf(axeSlot));
                }
            }
            breakStage = 2;
            state = State.Mining;
            return false;
        } else if (state == State.AwaitBreakPermissionAll && breakAllPermitted) {
            breakAllPermitted = false;
            startBreakAll();
            return true;
        }
        return false;
    }

    /** State-transition + checkpoint-consumption debug logs (debug-prints setting). */
    private void logTickDebug() {
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
    }

    /** Slave announces liveness so the master's registered-slave list survives master
     *  re-toggles; also reconnects when the master link was lost (e.g. master restart). */
    private void sendSlaveHeartbeat() {
        if (!SlaveSystem.isSlave) return;
        if (++heartbeatTicks >= 40) {
            heartbeatTicks = 0;
            SlaveSystem.sendMessageToMaster("heartbeat");
            if (!LocalTCPTransport.isRunning()) {
                SlaveSystem.setTcpAddress(tcpIp.get(), tcpPort.get());
                if (LocalTCPTransport.isRunning()) info("Reconnected to master");
            }
        }
    }

    /** Chest/table interaction retry timer. */
    private void tickInteractRetry() {
        if (interactTimeout > 0) {
            interactTimeout--;
            if (interactTimeout == 0) {
                info("Interaction timed out. Interacting again...");
                interactWithBlock(lastInteractedChest);
            }
        }
    }

    /** Pending on-demand hotbar swap (material found in the inventory, not hotbar). */
    private boolean consumeHotbarSwap() {
        if (toBeSwappedSlot == -1) return false;
        swapIntoHotbar(toBeSwappedSlot);
        toBeSwappedSlot = -1;
        if (postSwapDelay.get() != 0) {
            timeoutTicks = postSwapDelay.get();
            return true;
        }
        return false;
    }

    /** Restock backlog: one chest-slot take per delay tick while the chest screen stays open. */
    private boolean drainRestockBacklog() {
        if (restockBacklogSlots.size() == 0) return false;
        int slot = restockBacklogSlots.remove(0);
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 1, SlotActionType.QUICK_MOVE, mc.player);
        if (restockBacklogSlots.isEmpty()) {
            if (state.equals(State.AwaitRestockResponse)) {
                endRestocking();
            }
        } else {
            timeoutTicks = invActionDelay.get();
        }
        return true;
    }

    /** Deposit backlog: one matching stack per delay tick while the chest screen stays
     *  open; when it drains, hop to the next correct chest or continue the queued route.
     *  A chest with no room left for the item is marked full and the deposit reroutes
     *  to another registered chest. */
    private boolean drainDepositBacklog() {
        if (state != State.AwaitDepositResponse || depositBacklogSlots.isEmpty()) return false;
        int slot = depositBacklogSlots.remove(0);
        ItemStack pending = mc.player.currentScreenHandler.slots.get(slot).getStack();
        if (pending.isEmpty()) return true;
        if (!chestHasRoomFor(pending)) {
            fullDepositChests.add(lastInteractedChest);
            info("§eChest " + lastInteractedChest.toShortString() + " is full - depositing in another registered chest.");
            Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
            depositBacklogSlots.clear();
            if (nextDepositChest != null) queueDepositChestAtHead(nextDepositChest);
            timeoutTicks = postRestockDelay.get();
            state = State.Walking;
            return true;
        }
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
        if (depositBacklogSlots.isEmpty()) {
            Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
            if (nextDepositChest != null) queueDepositChestAtHead(nextDepositChest);
            timeoutTicks = postRestockDelay.get();
            state = State.Walking;
        } else {
            timeoutTicks = invActionDelay.get();
        }
        return true;
    }

    /** Serpentine pass feed (RESET ladder): keep a break target armed from the segment
     *  around the bot so the pass breaks everything in interaction range wherever it
     *  walks - no walking past blocks, noobline included. NO item targeting from the
     *  feed - queuing item checkpoints here created an infinite loop (feed detects item
     *  outside segment bounds -> queues checkpoint -> handler scans segment -> CLEAN ->
     *  feed detects the same item again). Items are collected by vanilla proximity on
     *  the serpentine path + segment confirmation + verify walk. */
    private void armResetFeedTarget() {
        if (state != State.Mining || !fullSweepActive || resetLadderCol >= 32
            || checkpoints.isEmpty()
            || !"resetPass".equals(checkpoints.getFirst().getRight().getLeft())
                && !"resetStation".equals(checkpoints.getFirst().getRight().getLeft())) return;
        // ARM BLOCK TARGETS: scan ONLY within z±6 of the bot. Prevents
        // targeting blocks far ahead while missing blocks behind. Items
        // collected by vanilla proximity + segment confirmation + verify walk.
        if (nextBlockPos == null) {
            int botZ = mc.player.getBlockZ();
            int bx0 = lowerMapCorner.getX();
            int bx1 = bx0 + 127;
            int bz0 = Math.max(lowerMapCorner.getZ() - 1, botZ - 6);
            int bz1 = Math.min(lowerMapCorner.getZ() + 127, botZ + 6);
            nextBlockPos = findNearestBlockInArea(bx0, bx1, bz0, bz1);
        }
        // FEED-JAM recovery: an armed target beyond interaction range never turns
        // air and blocks re-arming (feed only arms when nextBlockPos == null) —
        // the pass would walk on breaking nothing. After 10 stuck ticks, drop the
        // stale target so the feed re-arms (the block gets re-targeted on a later
        // pass; the arrival scan and verify walk still gate completeness).
        if (nextBlockPos != null
            && mc.player.getEyePos().distanceTo(nextBlockPos.toCenterPos()) > interactionRange.get()) {
            if (nextBlockPos.equals(jamWatchTarget)) {
                jamWatchTicks++;
                if (jamWatchTicks >= 10) {
                    info("§6[RESET-VERIFY]§7 feed re-armed: dropped stale out-of-range target "
                        + nextBlockPos.toShortString() + " (dist ~"
                        + Math.round(mc.player.getEyePos().distanceTo(nextBlockPos.toCenterPos()))
                        + " > " + interactionRange.get() + ") after " + jamWatchTicks + " ticks.");
                    nextBlockPos = null;
                    jamWatchTarget = null;
                    jamWatchTicks = 0;
                }
            } else {
                jamWatchTarget = nextBlockPos;
                jamWatchTicks = 1;
            }
        } else {
            jamWatchTarget = null;
            jamWatchTicks = 0;
        }
    }

    /** Break-confirmation processing — THE single processor, and it must run EVERY
     *  tick at this point of the pipeline: the tick flow returns early on many ticks
     *  (every checkpoint arrival handler returns), and a second processor further down
     *  used to double-decrement the same timers and silently remove expired entries on
     *  all those ticks — failed fastBreaks then NEVER reached the vanilla fallback.
     *  Confirmed (now air) → remove. Expired + still solid → needsVanillaBreak. */
    private void tickBreakConfirmations() {
        breakSweepActedThisTick = false;
        if (++rateTick >= 20) {
            rateTick = 0;
            if (breakSentPerSec > 0 || breakConfirmedPerSec > 0 || placeSentPerSec > 0) {
                info("§d[RATE]§7 break §f" + breakSentPerSec + "/s sent §a" + breakConfirmedPerSec
                    + "/s confirmed§7 | place §f" + placeSentPerSec + "/s sent§7");
            }
            breakSentPerSec = 0; breakConfirmedPerSec = 0; placeSentPerSec = 0;
        }
        if (!breakPending.isEmpty()) {
            int confirmedThisTick = 0, expiredThisTick = 0;
            Iterator<BlockPos> pendingIt = breakPending.keySet().iterator();
            while (pendingIt.hasNext()) {
                BlockPos pending = pendingIt.next();
                int ticksLeft = breakPending.get(pending) - 1;
                if (miningBlockState(pending).isAir()) {
                    pendingIt.remove();
                    confirmedThisTick++;
                    vanillaBreakRetries.remove(pending);
                } else if (ticksLeft <= 0) {
                    // fastBreak failed — multi-tick block. Vanilla progress REQUIRES the
                    // block in server reach: at speed-hack pace the bot outruns the echo
                    // and expired blocks behind it could never progress (live 2026-10-21:
                    // 51 blocks frozen in vanillaQ). Still-in-reach -> vanilla; behind
                    // the bot -> drop it, the breakAllDone gate chases it on foot.
                    if (mc.player.getEyePos().distanceTo(pending.toCenterPos()) <= 4.4) {
                        needsVanillaBreak.add(pending);
                    }
                    int retries = vanillaBreakRetries.getOrDefault(pending, 0) + 1;
                    vanillaBreakRetries.put(pending, retries);
                    if (retries == 4) {
                        warning("§6[RESET-VERIFY]§c PERSISTENT REJECTION: " + pending.toShortString()
                            + " expired into vanilla fallback " + retries
                            + " times without ever being server-confirmed - the fastBreak/vanilla "
                            + "cycle is not making progress on this block.");
                    }
                    expiredThisTick++;
                    pendingIt.remove();
                } else {
                    breakPending.put(pending, ticksLeft);
                }
            }
            breakConfirmedPerSec += confirmedThisTick;
            if (debugPrints.get() && (confirmedThisTick + expiredThisTick) > 0) {
                info("§3[dbg] break-confirmation: §aserver-confirmed=" + confirmedThisTick
                    + "§3 expired-to-vanilla=§e" + expiredThisTick
                    + "§3 inFlight=" + breakPending.size() + " vanillaQ=" + needsVanillaBreak.size());
            }
        }
        // Clean vanilla-fallback set when blocks break
        if (!needsVanillaBreak.isEmpty()) {
            needsVanillaBreak.removeIf(pos -> miningBlockState(pos).isAir());
        }
    }

    /** The armed break target: clear it once the world shows air, otherwise keep
     *  sending distinct breaks at the per-tick budget. */
    private void pumpArmedBreakTarget() {
        if (!state.equals(State.Mining) || nextBlockPos == null) return;
        if (miningBlockState(nextBlockPos).isAir()) {
            nextBlockPos = null;
            state = State.Mining;
        } else {
            // Distinct-position multi-send (25-30/s target): the per-tick stop-after-
            // one pacing was the rate cap - the client cannot see confirmations
            // within the same tick. See sendDistinctBreaks.
            sendDistinctBreaks(breaksPerTick.get(), fullSweepActive);
        }
    }

    /** Handle a pending block-entity interaction response packet, then any queued
     *  screen close. @return true when a packet was handled (tick stops). */
    private boolean processDeferredInventoryIO() {
        if (toBeHandledInvPacket != null) {
            handleInventoryPacket(toBeHandledInvPacket);
            toBeHandledInvPacket = null;
            return true;
        }
        if (closeNextInvPacket) {
            if (mc.currentScreen != null) {
                mc.player.closeHandledScreen();
            }
            closeNextInvPacket = false;
        }
        return false;
    }

    /** Checkpoint arrival: anti-stuck guard, the action dispatch, and the empty-queue
     *  fallbacks. @return true when the tick must stop; false when the queue still has
     *  a next checkpoint (caller re-reads the goal). */
    private boolean handleCheckpointArrival(Vec3d goal) {
        Pair<String, BlockPos> checkpointAction = checkpoints.get(0).getRight();
        if (debugPrints.get() && checkpointAction.getLeft() != null)
            info("Reached: §a" + checkpointAction.getLeft());
        if (snapToCheckpoints.get()) mc.player.setPosition(goal.x, mc.player.getY(), goal.z);
        // ANTI-STUCK: if the same action fires 10+ times consecutively (handler
        // re-queuing itself without progress), force-skip to the next checkpoint.
        // EXCEPTION — sweep-family actions: resetPass/resetStation (RESET ladder),
        // sweepTick/batchExit (batch straggler sweep) and breakAllDone (break-all
        // arrival gate) legitimately re-queue themselves for every straggler block,
        // item chase, and in-flight drop wait; the generic force-skip abandoned reset
        // segments mid-clear and STRANDED batches (removing the only queued sweepTick
        // leaves an empty route -> StandBy with the batch unfinished). Loop protection
        // comes from the arrival STALL DETECTORS and the sweep budget + progress-aware
        // finishBatch retries instead.
        boolean isSweepFamilyAction = fullSweepActive && ("resetPass".equals(checkpointAction.getLeft())
            || "resetStation".equals(checkpointAction.getLeft()))
            || "sweepTick".equals(checkpointAction.getLeft())
            || "batchExit".equals(checkpointAction.getLeft())
            || "breakAllDone".equals(checkpointAction.getLeft())
            || "collectRun".equals(checkpointAction.getLeft());
        if (isSweepFamilyAction) {
            stuckCheckpointCount = 0;
            lastCheckpointAction = checkpointAction.getLeft();
        } else if (checkpointAction.getLeft() != null
            && checkpointAction.getLeft().equals(lastCheckpointAction)) {
            stuckCheckpointCount++;
            if (stuckCheckpointCount > 10) {
                info("§eAnti-stuck: skipping repeated checkpoint '" + checkpointAction.getLeft() + "'.");
                stuckCheckpointCount = 0;
                lastCheckpointAction = null;
                checkpoints.remove(0);
                return true;
            }
        } else {
            lastCheckpointAction = checkpointAction.getLeft();
            stuckCheckpointCount = 0;
        }
        checkpoints.remove(0);

        switch (checkpointAction.getLeft()) {
            case "calculateRefill":
                refillFromCurrentPlan();
                return true;
            case "refill":
                state = State.AwaitRestockResponse;
                Utils.setForwardPressed(false);
                interactWithBlock(checkpointAction.getRight());
                return true;
            case "foodChest":
                state = State.AwaitFoodChestResponse;
                Utils.setForwardPressed(false);
                interactWithBlock(checkpointAction.getRight());
                return true;
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
                        return true;
                    }
                }
                break;
            case "printDone":
                verifyPrintPass();
                break;
            case "printDone2":
                if (!verifyPrintPass2()) return true;
                break;
            case "awaitBreak2":
                if (printRepairPending) break;   // repair pass: walk through lane -1 placing
                info("§aBatch " + currentBatch + " placed. Waiting for master's break(2) permission.");
                breakStage = 0;
                state = State.AwaitBreakPermission2;
                Utils.setForwardPressed(false);
                SlaveSystem.sendMessageToMaster("step:ready:" + currentBatch);
                return true;
            case "break2Done":
                return handleBreak2DoneArrival();
            case "awaitBreakAll":
                info("§aBatch " + currentBatch + " break(2) complete. Waiting for master's break-all permission.");
                state = State.AwaitBreakPermissionAll;
                Utils.setForwardPressed(false);
                SlaveSystem.sendMessageToMaster("step:break2done:" + currentBatch);
                return true;
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
                return handleBreakAllDoneArrival();
            case "collectRun":
                return handleCollectRunArrival();
            case "batchExit":
                // North exit after break-all: verify the window is fully recycled
                // (combined sweep re-queues batchExit while stragglers remain), then
                // report the batch done to the master and stand by.
                finishBatch();
                return true;
            case "stepClosingReady":
                if (!verifyCurrentPlan("Closing")) return true;
                info("§aClosing placement verified. Waiting for the master's final walk.");
                SlaveSystem.sendMessageToMaster("step:ready:close");
                state = State.Walking;
                break;
            case "stepClosingWait":
                state = State.AwaitClosingBreak;
                Utils.setForwardPressed(false);
                return true;
            case "stepClosingDone":
                finishClosing();
                return true;
            case "sweepTick":
                return handleSweepTickArrival();
            case "resetPass":
            case "resetStation":
                return handleResetArrival(checkpointAction);
            case "fullSweepDone":
                fullSweepActive = false;
                clearResetPending();   // the RESET survived to completion
                state = State.StandBy;
                Utils.setForwardPressed(false);
                SlaveSystem.sendMessageToMaster("step:done:" + Math.max(currentBatch, 0));
                info("§6[RESET-VERIFY]§7 completion state: breakPending(in-flight server confirms)=" + breakPending.size()
                    + " vanillaQueue=" + needsVanillaBreak.size()
                    + " persistentRejections=" + vanillaBreakRetries.size()
                    + " - all should be 0 at completion.");
                info("§aRESET complete - print area cleared, items deposited, standby.");
                return true;
            case "depositUnneeded":
                // Prep-trip filtered dump: the backlog was PRE-FILLED with unneeded-
                // item slots at queue time - the deposit handler keeps them as-is.
            case "depositBuilding":
                state = State.AwaitDepositResponse;
                Utils.setForwardPressed(false);
                interactWithBlock(checkpointAction.getRight());
                return true;
        }
        if (checkpoints.isEmpty()) {
            // Route exhausted with no successor queued (e.g. end of a pass):
            // stand by cleanly instead of indexing into the empty queue
            state = State.StandBy;
            Utils.setForwardPressed(false);
            return true;
        }
        return false;
    }

    /** break2Done: either queue the second repair pass, verify break(2), or hand back
     *  to the route. @return true when the tick must stop. */
    private boolean handleBreak2DoneArrival() {
        if (printRepairPending) {
            // Repair pass 2: re-print any still-missing blocks from LANE 2
            // (same clean feet-level corridor as the main print pass), then
            // proceed to the normal break(2) wait.
            printRepairPending = false;
            double repairX = lowerMapCorner.getX() + 2 * currentBatch + 2 + 0.5;  // lane 2 (borrow-west) center
            // FIXED CYCLE: repair pass N->S like the print pass (no mirroring)
            double rStartZ = lowerMapCorner.getZ() - 2;
            double rFarZ = lowerMapCorner.getZ() + 128.7;
            checkpoints.add(0, new Pair(new Vec3d(repairX, lowerMapCorner.getY(), rStartZ), new Pair("break2Done", null)));
            checkpoints.add(0, new Pair(new Vec3d(repairX, lowerMapCorner.getY(), rFarZ), new Pair("awaitBreak2", null)));
            checkpoints.add(0, new Pair(new Vec3d(repairX, lowerMapCorner.getY(), rFarZ), new Pair("printDone2", null)));
            checkpoints.add(0, new Pair(new Vec3d(repairX, lowerMapCorner.getY(), rStartZ), new Pair("sprint", null)));
            state = State.Walking;
            return true;
        }
        if (!verifyBreak2()) return true;
        state = State.Walking;
        return false;
    }

    /** breakAllDone ARRIVAL GATE — same logic as the RESET serpentine: the breaking
     *  pass walked the lane, but the batch is NOT handed off to batchExit until the
     *  window is recycled SERVER-CONFIRMED (client air = server air — fastBreak never
     *  predicts) and its drops are collected. In-flight drops are dwelt for;
     *  stragglers are chased through the hybrid Mining loop.
     *  @return true when the tick must stop; false = advance to batchExit. */
    private boolean handleBreakAllDoneArrival() {
        ensurePickaxeSlot8();
        breakStage = 4;
        state = State.Mining;
        int remainingBlocks = countRemainingPlanBlocks();
        int[] census = itemCensusInBatchWindow();
        int bwx0 = batchWindowWestX() - 2;
        int pendBrk = countPendingInRange(bwx0, bwx0 + 7, lowerMapCorner.getZ() - 3, lowerMapCorner.getZ() + 129);
        int vanillaQ = countVanillaInRange(bwx0, bwx0 + 7, lowerMapCorner.getZ() - 3, lowerMapCorner.getZ() + 129);
        // STALL DETECTOR (same signature discipline as resetPass): blocks +
        // item states + position — any progress changes it. 80 frozen rounds
        // advance to the exit gate with a dump (the sweep there re-verifies).
        String baSig = "ba" + currentBatch + "|b" + remainingBlocks
            + "|i" + census[0] + "," + census[1] + "," + census[2] + "," + census[3]
            + "|p" + mc.player.getBlockX() + "," + mc.player.getBlockZ();
        boolean baChanged = !baSig.equals(resetStallSig);
        if (!baChanged) {
            resetStallRounds++;
            if (resetStallRounds > 80) {
                warning("§6[BREAKALL-VERIFY]§c STALL: batch " + currentBatch
                    + " exit frozen for 80+ rounds - blocks=" + remainingBlocks
                    + " items[ready=" + census[0] + " falling=" + census[1]
                    + " pickupDelay=" + census[2] + " owned=" + census[3] + "]"
                    + " pendBrk=" + pendBrk + " - advancing to the exit gate (sweep re-verifies).");
                resetStallRounds = 0;
                resetStallSig = null;
                return false;   // queue advances to batchExit
            }
        } else {
            resetStallSig = baSig;
            resetStallRounds = 0;
        }
        if (baChanged) {
            info("§6[BREAKALL-VERIFY]§7 batch " + currentBatch + " pass end: blocks=" + remainingBlocks
                + " items[ready=" + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2]
                + " owned=" + census[3] + "] pendBrk=" + pendBrk + " vanillaQ=" + vanillaQ
                + (freeInventoryCapacity() == 0 ? " §cINV-FULL(item chase skipped)" : ""));
        }
        BlockPos straggler = nearestRemainingPlanBlock();
        int totalDrops = census[0] + census[1] + census[2];
        if (straggler == null && totalDrops == 0) {
            info("§aBreak-all batch " + currentBatch + " CLEAN - fully confirmed.");
            return false;   // queue advances to batchExit (finishBatch re-verifies + completes)
        }
        if (straggler != null) {
            // BLOCKS FIRST, always - no collection happens until break(4) has
            // actually finished (blocks = 0, server-confirmed).
            nextBlockPos = straggler;
            checkpoints.addFirst(new Pair(straggler.toCenterPos(), new Pair("breakAllDone", null)));
            return true;
        }
        if (census[0] == 0) {
            // Only in-flight drops remain: they land within ~10-20 ticks and a
            // departing bot never returns for them - dwell and re-check.
            checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("breakAllDone", null)));
            return true;
        }
        // break(4) COMPLETE (blocks=0) and ready drops on the ground: ONE
        // single south-to-north collection run. The break pass always ends at
        // the SOUTH end, so the run starts there and only ever advances
        // north (side-steps to drops are east-west; no NS reversals).
        info("§6[BREAKALL-VERIFY]§7 batch " + currentBatch + ": blocks=0, " + census[0]
            + " ready drops - single south-to-north collection run.");
        checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("collectRun", null)));
        state = State.Walking;
        return true;
    }

    /** collectRun SINGLE-RUN COLLECTOR: re-target the nearest drop at/ahead of the bot
     *  (z never moves more than 1.5 blocks south) - the run sweeps the whole window
     *  monotonically south -> north and ends clean. No collection happens anywhere
     *  before break(4) completes. Always ends the tick. */
    private boolean handleCollectRunArrival() {
        state = State.Walking;
        int[] cCensus = itemCensusInBatchWindow();
        if (cCensus[0] + cCensus[1] + cCensus[2] == 0) {
            // The run ends at the NORTH end - replace the stale south-end
            // batchExit (queued by startBreakAll next to breakAllDone) with
            // one HERE: the exit gate is position-independent, and walking a
            // full lane back south just to reach it is pure waste.
            checkpoints.removeIf(cp -> "batchExit".equals(cp.getRight().getLeft()));
            checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
            info("§aBatch " + currentBatch + " collection run complete - window clean, exiting in place.");
            return true;
        }
        if (cCensus[0] == 0) {
            // everything left is in flight - dwell one re-check
            checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("collectRun", null)));
            return true;
        }
        ItemEntity next = nearestCollectTarget();
        if (next == null) {
            // only unreachable/blacklisted drops remain - hand off to the exit
            // gate HERE (finishBatch census will name them)
            checkpoints.removeIf(cp -> "batchExit".equals(cp.getRight().getLeft()));
            checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
            warning("§6[BREAKALL-VERIFY]§e batch " + currentBatch + ": " + cCensus[0]
                + " drops unreachable in the collection run - exiting to the gate in place.");
            return true;
        }
        checkpoints.addFirst(new Pair(new Vec3d(next.getX(), next.getY(), next.getZ()), new Pair("collectRun", null)));
        return true;
    }

    /** sweepTick COMBINED spatial-awareness SWEEP: nearest actionable target (block or
     *  item), using the shared findNearestGroundItem helper. Always ends the tick. */
    private boolean handleSweepTickArrival() {
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
                return true;
            } else if (nearestItem != null) {
                targetGroundItem(nearestItem, "sweepTick");
                return true;
            }
        }
        // Exit with visible content remaining = budget or inventory driven:
        // name the reason — the finishBatch gate re-verifies and takes over.
        if (nearestBlock != null || nearestItem != null) {
            info("§6[SWEEP-VERIFY]§7 batch " + currentBatch + " sweep pass ending early: "
                + (freeInventoryCapacity() == 0 ? "inventory full" : "budget exhausted")
                + " with " + (nearestBlock != null ? "blocks " : "") + (nearestItem != null ? "+ items " : "")
                + "still visible - exit gate will re-verify.");
        }
        checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
        state = State.Walking;
        return true;
    }

    /** resetPass/resetStation ARRIVAL: ALWAYS Mining during reset — Walking disables
     *  the feed (no break targets armed), so any block in the path walls the bot. The
     *  serpentine walk already cleared everything in range; confirm the segment with a
     *  bounded scan; only stragglers (bounced drops, unconfirmed breaks) get targeted
     *  cleanup here - no greedy nearest-first hopping across the segment. Always ends
     *  the tick (the ladder queue advances on its own). */
    private boolean handleResetArrival(Pair<String, BlockPos> checkpointAction) {
        state = State.Mining;
        // PASS ARRIVAL: decode the (col, seg) anchor the queue attached, with a decode
        // cross-check - a mismatch means the arrival scan verifies the WRONG rows.
        if (checkpointAction.getRight() != null) {
            resetLadderCol = Math.min(31, (checkpointAction.getRight().getX() - lowerMapCorner.getX()) / 4);
            int anchorRelZ = checkpointAction.getRight().getZ() - lowerMapCorner.getZ();
            int actualSeg = Math.min(RESET_SEG_START.length - 1, Math.max(0, anchorRelZ / 8));
            resetLadderSeg = resetZToSeg(anchorRelZ);
            if (resetLadderSeg != actualSeg) {
                warning("§6[RESET-VERIFY]§e SEGMENT DECODE BUG at col " + resetLadderCol
                    + ": checkpoint is segment " + actualSeg + " (rows " + (actualSeg * 8) + "-" + (actualSeg * 8 + 7)
                    + ") but resetZToSeg decoded " + resetLadderSeg
                    + " (rows " + (RESET_SEG_START[resetLadderSeg]) + "-" + RESET_SEG_END[resetLadderSeg]
                    + ") - the arrival scan below verifies the WRONG rows, the walked segment is NOT verified.");
            }
        }
        ItemStack ladderPick = mc.player.getInventory().getStack(8);
        if (!ladderPick.isIn(ItemTags.PICKAXES)) {
            // Worn pickaxes keep working mid-run (exchange only at the pre-batch
            // restock) - fetch only when none is left at all
            movePickaxeToSlot8();
            if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                pauseForRestockTrip(true);
                return true;
            }
        }
        int lx0 = lowerMapCorner.getX() + 4 * resetLadderCol;
        int lx1 = lx0 + 3;
        int lz0 = lowerMapCorner.getZ() + RESET_SEG_START[resetLadderSeg] - (resetLadderSeg == 0 ? 1 : 0);  // segment 0 owns the noobline row too
        int lz1 = lowerMapCorner.getZ() + RESET_SEG_END[resetLadderSeg];
        // ITEM MARGIN window: blocks never move, but their drops skid — scan
        // (and chase) items this far past the segment in every horizontal
        // direction, including outside the print box at its edges.
        int ix0 = lx0 - RESET_ITEM_MARGIN, ix1 = lx1 + RESET_ITEM_MARGIN;
        int iz0 = lz0 - RESET_ITEM_MARGIN, iz1 = lz1 + RESET_ITEM_MARGIN;
        if (freeInventoryCapacity() == 0 && findNearestItemInArea(ix0, ix1, iz0, iz1) != null) {
            Pair<BlockPos, Vec3d> ladderChest = getBestDepositChest();
            if (ladderChest != null) {
                checkpoints.clear();
                checkpoints.add(new Pair(ladderChest.getRight(), new Pair("depositBuilding", ladderChest.getLeft())));
                queueResetLadder(resetLadderCol, resetLadderSeg);
                prependNorthCorridor(ladderChest.getRight().x);
                state = State.Mining;
                info("§7Inventory full - depositing, then resuming the reset ladder.");
                state = State.Mining;  // resume in Mining: feed stays active on the return walk
                return true;
            }
        }
        BlockPos lTarget = findNearestBlockInArea(lx0, lx1, lz0, lz1);
        ItemEntity lItem = freeInventoryCapacity() > 0 ? findNearestItemInArea(ix0, ix1, iz0, iz1) : null;
        // RESET-VERIFY census: everything the CLEAN verdict is about to be based on,
        // including content the filtered scans above cannot see.
        int[] census = itemCensusInArea(ix0, ix1, iz0, iz1);
        int pendBrk = countPendingInRange(lx0, lx1, lz0, lz1);
        int vanilla = countVanillaInRange(lx0, lx1, lz0, lz1);
        // STALL DETECTOR (replaces the generic anti-stuck force-skip for this
        // handler): the signature covers block count, every item state and the
        // bot position — ANY progress changes it (blocks break, drops land,
        // states tick over, the bot walks). Only 80 consecutive FROZEN arrivals
        // force-advance, with a full dump of what is being left behind.
        String arrivalSig = resetLadderCol + ":" + resetLadderSeg + "|b" + countBlocksInArea(lx0, lx1, lz0, lz1)
            + "|i" + census[0] + "," + census[1] + "," + census[2] + "," + census[3]
            + "|p" + mc.player.getBlockX() + "," + mc.player.getBlockZ();
        boolean sigChanged = !arrivalSig.equals(resetStallSig);
        if (!sigChanged) {
            resetStallRounds++;
            if (resetStallRounds > 80) {
                warning("§6[RESET-VERIFY]§c STALL: col " + resetLadderCol + " seg " + resetLadderSeg
                    + " frozen for 80+ arrival rounds - blocks=" + countBlocksInArea(lx0, lx1, lz0, lz1)
                    + " items+m" + RESET_ITEM_MARGIN + "[ready=" + census[0] + " falling=" + census[1]
                    + " pickupDelay=" + census[2] + " owned=" + census[3] + "]"
                    + " pendBrk=" + pendBrk + " - advancing WITHOUT clearing (verify walk will re-scope).");
                resetStallRounds = 0;
                resetStallSig = null;
                return true;   // no re-queue: the ladder advances
            }
        } else {
            resetStallSig = arrivalSig;
            resetStallRounds = 0;
        }
        // Census log only on state change: dwell waits and in-place breaking
        // fire arrivals every tick — logging each would flood the chat.
        if (sigChanged) {
            info("§6[RESET-VERIFY]§7 col " + resetLadderCol + " seg " + resetLadderSeg
                + " rows " + (lz0 - lowerMapCorner.getZ()) + "-" + (lz1 - lowerMapCorner.getZ())
                + " §fblocks=" + (lTarget != null ? "1+ @" + (lTarget.getX() - lowerMapCorner.getX()) + "," + (lTarget.getY() - lowerMapCorner.getY()) + "," + (lTarget.getZ() - lowerMapCorner.getZ()) : "0")
                + " §7items+m" + RESET_ITEM_MARGIN + "[ready=" + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2] + " owned=" + census[3] + "]"
                + " §3pendBrk=" + pendBrk + " vanillaQ=" + vanilla
                + (freeInventoryCapacity() == 0 ? " §cINV-FULL(item scan skipped)" : ""));
        }
        if (lTarget == null && lItem == null) {
            if (census[1] + census[2] > 0) {
                // WAIT for in-flight drops instead of advancing: they land and
                // become pickup-able within ~10-20 ticks, and a sprinting bot
                // never returns for them. Dwell in place and re-check — the
                // state transitions keep the stall detector fed; a genuinely
                // frozen wait still force-advances after 80 rounds.
                checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("resetStation", null)));
                return true;
            }
            info("§aReset col " + resetLadderCol + " segment " + resetLadderSeg + " CLEAN - fully confirmed.");
            return true;   // ladder queue advances on its own
        }
        double bDist = lTarget != null ? mc.player.getEyePos().distanceTo(lTarget.toCenterPos()) : Double.MAX_VALUE;
        double iDist = lItem != null ? mc.player.getEyePos().distanceTo(lItem.getEntityPos()) : Double.MAX_VALUE;
        if (lTarget != null && bDist <= iDist) {
            if (bDist <= interactionRange.get()) {
                nextBlockPos = lTarget;
                // Same hybrid rules as the per-tick break loop - via the ONE
                // shared sender (pending-guarded: no re-send while a
                // confirmation is in flight).
                if (!breakPending.containsKey(lTarget)) sendHybridBreak(lTarget);
                checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("resetStation", null)));
                state = State.Mining;
                breakStage = 4;
            } else {
                // Keep Mining state — the feed arms targets during the walk,
                // so blocks in range get broken continuously. Setting Walking
                // here was the bug: no blocks broken while walking to targets.
                checkpoints.addFirst(new Pair(lTarget.toCenterPos(), new Pair("resetStation", null)));
                state = State.Mining;
            }
        } else {
            // in-cell item chase WITHOUT clearing the ladder queue
            checkpoints.addFirst(new Pair(new Vec3d(lItem.getX(), lItem.getY(), lItem.getZ()), new Pair("resetStation", null)));
            state = State.Mining;  // Mining: feed stays active, blocks in path get broken
        }
        return true;
    }

    /** YAW control while walking: during breaking sweep passes (sweepTick,
     *  fullSweepTick, resetStation, resetPass) the walking target is a BLOCK being
     *  chased - the camera must NOT follow it (that causes random-facing swings).
     *  Instead, keep the yaw locked to the dominant axis of movement (the
     *  serpentine/lane direction). fastBreak doesn't need any facing direction - the
     *  packets go out regardless. */
    private void steerTowardGoal(Vec3d goal, String nextAction) {
        boolean isBreakSweep = "sweepTick".equals(nextAction) || "fullSweepTick".equals(nextAction)
            || "resetStation".equals(nextAction) || "resetPass".equals(nextAction);
        if (isBreakSweep) {
            // Lock yaw to the cardinal direction of travel (N/S on lanes, E/W on corridors)
            double dx = goal.x - mc.player.getX();
            double dz = goal.z - mc.player.getZ();
            float lockedYaw;
            if (Math.abs(dz) > Math.abs(dx)) {
                lockedYaw = dz > 0 ? 0.0f : 180.0f;   // facing south / north
            } else {
                lockedYaw = dx > 0 ? -90.0f : 90.0f;  // facing east / west
            }
            mc.player.setYaw(lockedYaw);
        } else {
            // Normal yaw steering toward the walking goal
            double lookZ = goal.z;
            if (PlayerUtils.distanceTo(goal) > 2) {
                lookZ = mc.player.getZ() + Math.max(Math.min(goal.z - mc.player.getZ(), 1), -1);
            }
            mc.player.setYaw((float) Rotations.getYaw(new Vec3d(goal.x, goal.y, lookZ)));
        }
    }

    /** Sprint policy: transit and work legs sprint unless sprint-mode is Off. */
    private void applySprintMode(String nextAction) {
        if (sprinting.get() != SprintMode.Off) mc.player.setSprinting(true);
    }

    /** The next place/break work target for the current state (null = none). */
    private BlockPos pickWorkTarget() {
        if (state.equals(State.Walking)) {
            return (currentBuildPlan != null && breakStage == 0) ? getNextBatchPlacementPos() : null;
        }
        return fullSweepActive ? nextBlockPos
            : breakStage != 0 ? getNextBatchBreakPos() : null;
    }

    /** ROLLING HOTBAR LOOKAHEAD (user rule: "always, for the whole batch, not just at
     *  start"). FIRST-USE-Z window scan + BELADY MULTI-SWAP: walking order is N->S, so
     *  scan the plan's z-WINDOW AHEAD [botZ-2, botZ+24] and record each material's
     *  FIRST-USE z; ALL missing window materials are staged (cascade of up to 4
     *  swaps/sec), each evicting the resident with the FARTHEST next-use z (true
     *  Belady). Multi-stack materials: an emptied hotbar stack counts as missing, so
     *  the next carried stack of the same item stages automatically. */
    private void rollingHotbarLookahead() {
        HashMap<Item, Integer> firstUseZ = new HashMap<>();
        int botZNow = mc.player.getBlockZ();
        for (Map.Entry<BlockPos, Block> ue : currentBuildPlan.entrySet()) {
            BlockPos up = ue.getKey();
            int uz = up.getZ();
            if (uz < botZNow - 2 || uz > botZNow + 24) continue;   // the walking window
            if (miningBlockState(up).isAir()) continue;
            Item uItem = ue.getValue().asItem();
            Integer prev = firstUseZ.get(uItem);
            if (prev == null || uz < prev) firstUseZ.put(uItem, uz);
        }
        int swapsDone = 0;
        ArrayList<Item> bringOrder = new ArrayList<>();
        ArrayList<Integer> bringZs = new ArrayList<>();
        for (Map.Entry<Item, Integer> fe : firstUseZ.entrySet()) {
            if (!isItemInHotbar(fe.getKey())) { bringOrder.add(fe.getKey()); bringZs.add(fe.getValue()); }
        }
        for (int a = 0; a < bringOrder.size(); a++) {
            for (int b = a + 1; b < bringOrder.size(); b++) {
                if (bringZs.get(b) < bringZs.get(a)) {
                    Item tI = bringOrder.get(a); bringOrder.set(a, bringOrder.get(b)); bringOrder.set(b, tI);
                    Integer tZ = bringZs.get(a); bringZs.set(a, bringZs.get(b)); bringZs.set(b, tZ);
                }
            }
        }
        for (int bi = 0; bi < bringOrder.size() && swapsDone < 4; bi++) {
            Item inItem = bringOrder.get(bi);
            int inZ = bringZs.get(bi);
            int srcSlot = -1;
            for (int is = 9; is < 36 && srcSlot == -1; is++) {
                ItemStack iStack = mc.player.getInventory().getStack(is);
                if (!iStack.isEmpty() && iStack.getItem().equals(inItem)) srcSlot = is;
            }
            if (srcSlot == -1) continue;
            int evictSlot = -1;
            int worstZ = Integer.MIN_VALUE;
            for (int hs : availableHotBarSlots) {
                ItemStack eStack = mc.player.getInventory().getStack(hs);
                if (eStack.isEmpty()) { evictSlot = hs; worstZ = Integer.MAX_VALUE; break; }
                Integer eZ = firstUseZ.get(eStack.getItem());
                int eDist = (eZ == null) ? Integer.MAX_VALUE : eZ;
                if (eDist > worstZ) { worstZ = eDist; evictSlot = hs; }
            }
            if (evictSlot == -1) break;
            if (worstZ < inZ) break;
            Utils.performSwap(srcSlot, evictSlot);
            swapsDone++;
            info("§7Hotbar rolling: §f" + inItem.getName().getString() + "§7 swapped in (slot " + evictSlot
                + ", first use ~" + (inZ - botZNow) + " rows ahead).");
        }
    }

    /** The tick's work engine once a target passed the range gate: batch multi-place
     *  or batch break (reserved pickaxe + distinct sends). Sets nextBlockPos before
     *  calling (the armed target). */
    private void actOnWorkTarget() {
        if (state.equals(State.Walking)) {
            if (breakStage == 0 && currentBuildPlan != null) {
                // DISTINCT-POSITION MULTI-PLACE (user target: 25-30 places/s). The old
                // loop stopped after one place: the client cannot see the server's
                // placement echo for 1-2 ticks, so the air-check always failed and
                // broke the loop (~10-12/s cap). Now: send up to blocksPerTick
                // DIFFERENT positions per tick (each on a 4-tick cooldown), stop only
                // on a swap/restock need.
                HashSet<BlockPos> attempted = new HashSet<>();
                int placed = 0;
                for (int pick = 0; pick < blocksPerTick.get() * 3 && placed < blocksPerTick.get(); pick++) {
                    BlockPos target = getNextBatchPlacementPos(attempted);
                    if (target == null) break;
                    attempted.add(target);
                    nextBlockPos = target;
                    placementCooldowns.put(target, 4);  // ~0.2s before retry
                    placementAttempts.merge(target, 1, Integer::sum);  // adaptive-pace cap
                    int code = tryPlacingBlock();
                    if (code == 1) { placed++; placeSentPerSec++; }
                    else break;  // swap pending or material missing — tick handles it
                }
            }
            return;
        }
        // Batch breaking always swings the reserved pickaxe slot (hotbar slot 8)
        ItemStack reserved = mc.player.getInventory().getStack(8);
        if (!reserved.isIn(ItemTags.PICKAXES)) {
            // Mid-run a worn pickaxe keeps swinging (exchanges happen ONLY at the
            // pre-batch restock) - reaching here means it is GONE (broke). Arm any
            // carried one; with none left the fetch trip is the only recovery.
            movePickaxeToSlot8();
            if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                pauseForRestockTrip(true);
            }
            return;
        }
        mc.player.getInventory().setSelectedSlot(8);

        // Target new blocks — HYBRID: fastBreak (instant) for new targets,
        // vanilla mining for blocks fastBreak already failed on (multi-tick).
        // During RESET (fullSweepActive), currentBuildPlan is null so
        // getNextBatchBreakPos() returns nothing — use the ring-scanner instead.
        // Skipped entirely when the Mining-state loop earlier in the tick already
        // sent packets this tick: both loops acting on the same target doubles the
        // packets and re-resets server break-progress on vanilla targets.
        if (breakSweepActedThisTick) return;
        sendDistinctBreaks(breaksPerTick.get(), fullSweepActive && currentBuildPlan == null);
    }

    // Restocking

    Pair<BlockPos, Vec3d> getBestChest(Item item) {
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
        // Lowest chest as 1. criterion (fixed preference since the legacy layer engine went away)
        for (Pair<BlockPos, Vec3d> p : list) {
            //Skip chests that have already been checked
            if (checkedChests.contains(p.getLeft())) continue;
            if (bestList.isEmpty() || bestList.getFirst().getLeft().getY() >= p.getLeft().getY()) {   // prefer the LOWEST chest (layer preference is fixed since the legacy engine went away)
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

    /** Compute what the current plan still needs, drop what is in the way, and queue the
     *  refill trip - the ONE entry point shared by the calculateRefill checkpoint, batch
     *  prep, and the mid-print supply run. */
    private void refillFromCurrentPlan() {
        HashMap<Item, Integer> requiredItems = getRequiredItems();
        Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInfo =
            Utils.getInvInformation(requiredItems, availableSlots);
        refillBuildingInventory(invInfo.getRight());
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

    void interactWithBlock(BlockPos chestPos) {
        Utils.setForwardPressed(false);
        mc.player.setYaw((float) Rotations.getYaw(chestPos.toCenterPos()));
        mc.player.setPitch((float) Rotations.getPitch(chestPos.toCenterPos()));

        BlockHitResult hitResult = new BlockHitResult(chestPos.toCenterPos(), Utils.getInteractionSide(chestPos), chestPos, false);
        BlockUtils.interact(hitResult, Hand.MAIN_HAND, true);

        //Set timeout for chest interaction
        interactTimeout = retryInteractTimer.get();
        lastInteractedChest = chestPos;
    }

    /** ADAPTIVE PACE GOVERNOR - the single decision point for holding the bot's walk
     *  while work remains in reach. One method, two modes (never duplicated at call
     *  sites - the pre-dedup copies drifted and froze runs):
     *  PRINT mode (Walking, breakStage 0): hold while an in-range plan block is
     *  placeable RIGHT NOW - the placement branch's full preconditions (head-action
     *  allowlist - holding on a "refill" leg deadlocks, live 2026-10-20 01:36 - same
     *  picker, material carried, per-position attempt cap). 5s watchdog force-caps the
     *  position and walks on.
     *  BREAK mode (Mining, breakStage != 0): hold while a plan block in range still
     *  needs breaking, so break(2)/break-all passes clear rows as they walk instead of
     *  sprinting past them (live 2026-10-20: 86 blocks left standing in a 23s pass).
     *  10s watchdog releases and suppresses re-holding briefly. RESET is excluded
     *  (its plan is null - the serpentine feed + segment gates already pace it). */
    private boolean adaptivePaceHold() {
        if (state.equals(State.Walking) && breakStage == 0 && currentBuildPlan != null
            && !checkpoints.isEmpty()
            && ALLOW_PLACE_ACTIONS.contains(checkpoints.getFirst().getRight().getLeft())) {
            BlockPos placeNow = getNextBatchPlacementPos();
            if (placeNow != null && placementAttempts.getOrDefault(placeNow, 0) < 5) {
                Block planned = currentBuildPlan.get(placeNow);
                if (planned != null && isMaterialCarried(planned.asItem())) {
                    placeHoldTicks++;
                    if (placeHoldTicks > 100) {
                        placementAttempts.put(placeNow, 5);   // force-release: treat as capped
                        warning("§ePrint pace-hold watchdog: released a " + placeHoldTicks
                            + "-tick hold on " + placeNow.toShortString() + " - walking on.");
                        placeHoldTicks = 0;
                        return false;
                    }
                    return true;
                }
            }
            placeHoldTicks = 0;
        } else {
            placeHoldTicks = 0;
        }
        return false;
    }

    /** DISTINCT-POSITION MULTI-SEND (user target: 25-30 breaks/s). The old loop
     *  stopped after ONE send because the client cannot see the server's confirmation
     *  for 1-2 ticks - capping the rate at ~10-12/s. The pending-confirmation guard
     *  is PER-POSITION: sending N DIFFERENT positions in the same tick is safe (no
     *  re-send of a pending position ever happens). This sends up to maxSends distinct
     *  in-range targets per tick, skipping pending ones via the exclusion set.
     *  @return number of break actions sent this tick */
    private int sendDistinctBreaks(int maxSends, boolean fullSweep) {
        HashSet<BlockPos> attempted = new HashSet<>();
        // INSTA-BREAK REQUIRES THE PICKAXE IN HAND: the server computes break time
        // from the HELD item at packet time. With a building material selected
        // (left over from the print pass), same-tick START+STOP is rejected and
        // blocks stall into the vanilla queue (live 2026-10-20: 25/s sent but only
        // 3.8/s confirmed, 75 stragglers, batch timeout). The old second loop did
        // setSelectedSlot(8) every tick; this shared sender must too.
        if (mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
            mc.player.getInventory().setSelectedSlot(8);
        }
        // STICKY VANILLA: multi-tick breaking is stateful single-target — a NEW
        // BlockUtils.breakBlock on a different pos resets the previous one's progress.
        // Round-robining several vanilla targets per tick = nothing ever breaks (live
        // 2026-10-20 batch 13: 68 blocks frozen 80s "trying to break"). ONE vanilla
        // target at a time, held across ticks until air; everything else fastBreak.
        if (vanillaStickyTarget != null && (miningBlockState(vanillaStickyTarget).isAir()
            || mc.player.getEyePos().distanceTo(vanillaStickyTarget.toCenterPos()) > 4.4)) {
            vanillaStickyTarget = null;   // broke, or the bot outran it - never freeze the queue on it
        }
        int sent = 0;
        boolean vanillaSentThisTick = false;
        for (int pick = 0; pick < maxSends * 6 && sent < maxSends; pick++) {
            BlockPos breakTarget;
            if (pick == 0 && nextBlockPos != null && !attempted.contains(nextBlockPos)) {
                breakTarget = nextBlockPos;
            } else if (fullSweep) {
                breakTarget = findNearestPrintBoxBlock(attempted);
            } else {
                breakTarget = getNextBatchBreakPos(attempted);
            }
            if (breakTarget == null) break;                       // no more distinct candidates
            attempted.add(breakTarget);
            // NEAREST-FIRST + SERVER-REACH (user rule): pickers are distance-sorted
            // (nearest land first, farthest still sent when in range). Clamp the send
            // range to the SERVER's survival reach (~4.5) - client range 5.0 sends at
            // 4.5-5.0 get silently rejected, which starved the rate and caused the
            // rare stuck (block 'trying to break but nothing happens').
            if (mc.player.getEyePos().distanceTo(breakTarget.toCenterPos()) > Math.min(interactionRange.get(), 4.4)) continue;
            if (breakPending.containsKey(breakTarget)) continue;  // awaiting server confirm - next distinct
            boolean needsVanilla = needsVanillaBreak.contains(breakTarget);
            if (needsVanilla) {
                if (vanillaSentThisTick) continue;                 // one vanilla send per tick
                if (vanillaStickyTarget != null && !breakTarget.equals(vanillaStickyTarget)) {
                    continue;                                      // stick to the current vanilla target
                }
            }
            nextBlockPos = breakTarget;
            // BEST-TOOL AUTO-SWITCH (user rule, live 2026-10-20: oak/spruce planks,
            // crimson/warped hyphae in the art need an AXE — with the pickaxe they
            // take ~60 ticks/block). The server computes break time from the HELD item.
            if (!ensureBestToolFor(breakTarget)) continue;        // no usable tool - try next candidate
            sendHybridBreak(breakTarget);
            if (needsVanilla) {
                vanillaStickyTarget = breakTarget;
                vanillaSentThisTick = true;
            }
            sent++;
            breakSentPerSec++;
        }
        if (sent > 0) breakSweepActedThisTick = true;
        return sent;
    }

    /** Hold the FASTEST TOOL for the target block (axe for wood, pickaxe for stone).
     *  Ranks the registered toolSet against the block, finds a matching stack in the
     *  inventory, and selects it. Falls back to the reserved pickaxe (slot 8). */
    private boolean ensureBestToolFor(BlockPos target) {
        BlockState state = miningBlockState(target);
        ItemStack best = toolSet.isEmpty() ? null : ToolUtils.getBestTool(toolSet, state);
        ItemStack reserved = mc.player.getInventory().getStack(8);
        if (best == null || (reserved.isIn(ItemTags.PICKAXES)
            && new ItemStack(best.getItem()).getMiningSpeedMultiplier(state) <= reserved.getMiningSpeedMultiplier(state))) {
            best = reserved;
        }
        Item bestItem = best.getItem();
        if (mc.player.getMainHandStack().getItem().equals(bestItem)) return true;
        for (int slot : availableHotBarSlots) {
            if (!mc.player.getInventory().getStack(slot).isEmpty()
                && mc.player.getInventory().getStack(slot).getItem().equals(bestItem)) {
                mc.player.getInventory().setSelectedSlot(slot);
                return true;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (!mc.player.getInventory().getStack(slot).isEmpty()
                && mc.player.getInventory().getStack(slot).getItem().equals(bestItem)) {
                Utils.performSwap(slot, mc.player.getInventory().getSelectedSlot());
                return true;
            }
        }
        if (reserved.isIn(ItemTags.PICKAXES)) {
            mc.player.getInventory().setSelectedSlot(8);
            return true;
        }
        return false;
    }

    /** HYBRID BREAK SEND - the ONE copy of the break rules. One fastBreak per position
     *  per confirmation window (re-sending START/STOP every tick resets the server's
     *  elapsed-time validation); blocks fastBreak already failed on progress through
     *  vanilla mining instead (removal only via the air-check cleanup). Callers guard
     *  the pending window themselves when a skip vs stop-loop distinction matters.
     *  This logic MUST NOT be copied per call site - the pre-dedup copies drifted and
     *  caused the server-clock-reset starvation bug. */
    private void sendHybridBreak(BlockPos target) {
        if (needsVanillaBreak.contains(target)) {
            mc.player.getInventory().setSelectedSlot(8);
            BlockUtils.breakBlock(target, true);
        } else {
            ((IClientPlayerInteractionManager) mc.interactionManager).fastBreak(target);
            mc.player.swingHand(Hand.MAIN_HAND);
            breakPending.put(target, 6);   // 4-tick window: Folia's block-update echo can lag 2-3 ticks
        }
    }

    /** Is any of this building material currently carried (hotbar or main inventory)? */
    private boolean isMaterialCarried(Item needed) {
        for (int slot : availableSlots) {
            if (!mc.player.getInventory().getStack(slot).isEmpty()
                && mc.player.getInventory().getStack(slot).getItem().equals(needed)) return true;
        }
        return false;
    }

    /** Is this item currently held in one of the staging hotbar slots? */
    private boolean isItemInHotbar(Item item) {
        for (int slot : availableHotBarSlots) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.getItem().equals(item)) return true;
        }
        return false;
    }

    /** @return 1 = placement sent (hotbar hit), 0 = swap pending, -1 = material missing
     *  (swap/restock already triggered). The multi-place loop uses the code to keep
     *  sending distinct positions while material is in the hotbar and stop cleanly
     *  on a swap. */
    private int tryPlacingBlock() {
        if (currentBuildPlan == null) return -1;
        Item material;
        Block planned = currentBuildPlan.get(nextBlockPos);
        if (planned == null) return -1;
        material = planned.asItem();
        // PLACEMENT DIAGNOSTIC: log every batch placement with its lane (x), layer (y,
        // 99-102) and z relative to the bot — positive dz = AHEAD (south).
        if (debugPrints.get()) {
            double dz = nextBlockPos.getZ() - mc.player.getZ();
            info(String.format("§3[dbg] PLACE %s at x=%d y=%d z=%d (bot z=%.1f, dz=%+.1f %s)",
                material.getName().getString(), nextBlockPos.getX(), nextBlockPos.getY(),
                nextBlockPos.getZ(), mc.player.getZ(), dz,
                dz > 0 ? "§cAHEAD" : "§abehind"));
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
                return 1;
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
                return 0;
            }
        }
        if (lastSwappedMaterial == material) return 0;    //Wait for swapped material
        // Mid-print supply run: refill the missing material via the north checkpoint
        pauseForRestockTrip(false);
        return -1;
    }

    /**
     * Authoritative block state for MINING decisions - the live world, not the map cache.
     * The cache can go stale-air over a region (then nothing targets, nothing breaks, and the
     * bot silently walks into the un-mined wall); the world is always the truth for breaking.
     */
    BlockState miningBlockState(BlockPos pos) {
        return mc.world.getBlockState(pos);
    }

    // Path and Building Management

    boolean initialSetup() {
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
        master.beginStepMasterRun();
    }

    /** Start the measured master-side lifecycle once registration/configuration is complete. */

    // --- Progress persistence (crash/restart resume; file IO lives in StepFiles) ---

    /** RESET-IN-FLIGHT persistence: a module disable/crash mid-RESET must RESUME the
     *  reset on re-enable (user rule, 2026-10-20: "disable+re-enable after RESET ALL
     *  skipped the reset instead of completing it"). The scope is written when the
     *  ladder arms and deleted when it completes (or a new run supersedes it). */
    private void saveResetPending(int scopeCol) {
        StepFiles.saveResetPending(mapFolder, scopeCol);
    }

    private int loadResetPending() {
        return StepFiles.loadResetPending(mapFolder);
    }

    private void clearResetPending() {
        resetPendingScope = -1;
        StepFiles.clearResetPending(mapFolder);
    }

    /** Per-batch phase timing summary in mSS style (e.g. 5m10s). */



    /** The inventory slot holding the EXACT persisted working map (unnamed), or -1. */


    /** RESET: abort the slave, clear progress, re-fetch a map, start from batch 0. */

    /** Fill one empty map at the measured real checkpoint before any build or sweep begins. */





    /** Real platform/checkpoint coordinate: x is the platform's relative block coordinate. */


    /** Nearest lattice lane x (block 125+2j) to a world x, clamped to the lattice. */

    /** Prepend LATTICE transit legs to the queue: from the master's current position
     *  to (targetX, targetZ) moving ONLY along lane lines (vertical) and the final
     *  z (horizontal at the destination's z) - an L, never a diagonal. When starting
     *  OUTSIDE the area, the first leg is the north-west GATE (platform 125,14) -
     *  the single legal entry/exit. Callers queue their final checkpoint first. */






    /** Client-thread master scheduler.  This intentionally bypasses the legacy printer loop. */

    /** LATTICE WATCHDOG (user rule, 2026-10-20): inside the master area the bot may
     *  only stand/move on lattice lines (a lane x = 125+2j, or a row z of the 5
     *  stations). Anything else - e.g. a manual nudge or a stray walk - is logged with
     *  the position. Throttled: warned at most every 5s. */

    /** MAP-HOLD GUARD (user rule, 2026-10-20): while the module is enabled, the filled
     *  working map may ONLY be held during a sweep dwell - moving with it in hand
     *  re-records pixels and ruins progress. Any other hold (including the user
     *  selecting it manually) is cancelled and logged. */


    /** Wait checkpoint at the platform NEAREST the master's current position — post-sweep
     *  positions are already on/near a platform, so waiting is effectively in place. The
     *  master waits with the map STOWED (safety from map-closed, not distance), and every
     *  sweep starts from whichever end the master is at - so no repositioning leg is ever
     *  walked just to reach a wait position. */



    /** One tick of an active wait: heartbeat every 30s, hard timeout at 10 minutes with
     *  the expected signal named - a wait can never hang silently again. */





    /** Returns the pinned map slot, or the first unnamed filled map when pinning has not begun. */


    /** Select the pinned map without ever selecting a named reference map. */

    /** Holster the filled map before any slave build/break or station transit. */




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
        placementAttempts.clear();
        collectSkips.clear();
        clearResetPending();   // a new run supersedes any interrupted reset
        sweepReArmSig = null;
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        breakStage = 0;
        printRepairPending = false;
        break2Permitted = false;
        breakAllPermitted = false;
        fullSweepActive = false;   // no reset may survive into a batch
        currentBuildPlan = assembleBatch(k);
        info("§aBatch " + k + ": §b" + currentBuildPlan.size() + "§7 blocks planned. Restocking...");

        checkpoints.clear();
        state = State.Walking;
        // The whole batch route walks LANE 2 (x = 2k+2.5, block 2k+2's exact center —
        // the borrow-west lane): its only blocks are borrow recessives at y2, above the
        // bot's head, so nothing ever sits at feet level on the walking lane and every
        // batch lane is within interaction range.
        double walkX = lowerMapCorner.getX() + 2.0 * k + 2.5;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;

        // Restock materials (+ pickaxe if missing) and food BEFORE entering the print route.
        // refillBuildingInventory() adds its first chest at the head of the queue, so the
        // actual batch route is appended only after preparation is complete.
        queueStepBuildPreparation(walkX);

        // PHASE TOOL SLOTS (user rule, live 2026-10-21): printing needs NO tools -
        // the tool slots join the staging set during the print pass. The hotbar is
        // 0-8 ONLY (slot 9 is main inventory - passing it as performSwap's hotbar
        // argument crashed the client at batch 3, 02:27): tools park in hotbar
        // 7 (axe) + 8 (pickaxe); break(2)/break(4) moves them back in.
        {
            for (int ts = 7; ts <= 8; ts++) {
                ItemStack tStack = mc.player.getInventory().getStack(ts);
                if (!tStack.isEmpty() && (tStack.isIn(ItemTags.PICKAXES) || tStack.isIn(ItemTags.AXES))) {
                    int freeSlot = -1;
                    for (int fs = 9; fs < 36 && freeSlot == -1; fs++) {
                        if (mc.player.getInventory().getStack(fs).isEmpty()) freeSlot = fs;
                    }
                    if (freeSlot != -1) {
                        // hotbar -> main inventory: SWAP's button IS the hotbar index,
                        // and NO setSelectedSlot may run (it only accepts 0-8).
                        Utils.swapHotbarToInventory(ts, freeSlot);
                        if (!availableHotBarSlots.contains(ts)) availableHotBarSlots.add(ts);
                        info("§7Print phase: " + tStack.getItem().getName().getString() + " moved out of slot " + ts + " - staging slot freed.");
                    } else {
                        info("§7Print phase: slot " + ts + " tool stays put - inventory full, no free main slot.");
                    }
                }
            }
        }
        // HOTBAR LOOKAHEAD (user rule, live 2026-10-21): pre-arrange the hotbar with
        // the FIRST distinct materials of this batch's plan (walking order), so the
        // print pass rarely stalls on mid-run swaps - the old on-demand one-slot
        // swapping was the print delay. Runs before the route; one swap per material.
        {
            LinkedHashSet<Item> coming = new LinkedHashSet<>();
            for (Map.Entry<BlockPos, Block> le : currentBuildPlan.entrySet()) {
                if (coming.size() >= availableHotBarSlots.size()) break;
                coming.add(le.getValue().asItem());
            }
            int preSwapped = 0;
            int slotCursor = 0;
            for (Item comingItem : coming) {
                if (isItemInHotbar(comingItem)) continue;
                int srcSlot = -1;
                for (int is = 9; is < 36 && srcSlot == -1; is++) {
                    ItemStack isStack = mc.player.getInventory().getStack(is);
                    if (!isStack.isEmpty() && isStack.getItem().equals(comingItem)) srcSlot = is;
                }
                if (srcSlot == -1) continue;
                while (slotCursor < availableHotBarSlots.size()) {
                    int dest = availableHotBarSlots.get(slotCursor);
                    slotCursor++;
                    ItemStack destStack = mc.player.getInventory().getStack(dest);
                    if (destStack.isEmpty() || destStack.getItem().equals(comingItem)) {
                        Utils.performSwap(srcSlot, dest);
                        preSwapped++;
                        break;
                    }
                    // skip slots holding other needed materials
                    boolean destNeeded = false;
                    for (Item otherComing : coming) {
                        if (!destStack.isEmpty() && destStack.getItem().equals(otherComing)) { destNeeded = true; break; }
                    }
                    if (!destNeeded) { Utils.performSwap(srcSlot, dest); preSwapped++; break; }
                }
            }
            if (preSwapped > 0) info("§7Hotbar lookahead: pre-arranged " + preSwapped + " material slots for the print pass.");
        }

        // FIXED CYCLE (user rule, live 2026-10-20): every batch runs the same
        // directions - print N->S, break(2) S->N, break-all N->S (forced in
        // startBreakAll), collection S->N - ending each batch at the north end where
        // the next one starts. NO position-based mirroring: it flipped the parity
        // after any interrupt and manufactured dead NS walks. Repositioning happens
        // once, at the boundary, via the north corridor.
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("printDone", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
        prependLaneCentering(walkX);
        prependNorthCorridor(walkX);
    }

    /**
     * Queues the survival preparation shared by regular batches, priming, and closing.  The
     * queue order is material chests -> pickaxe chest (if needed) -> food -> hand setup; callers
     * then append their print route.  Every trip remains on the north corridor.
     */
    private void queueStepBuildPreparation(double stagingX) {
        refillFromCurrentPlan();
        boolean materialRestockQueued = !restockList.isEmpty();

        // UNNEEDED-MATERIAL DUMP (user rule, 2026-10-20), inlined body-only (hotswap-
        // safe): deposit carried building materials that NO remaining batch (or the
        // closing) will ever place - leftovers from finished batches otherwise squat on
        // slots for the rest of the run. Pre-fills depositBacklogSlots (the deposit
        // handler treats a pre-filled backlog at chest-open as a filtered dump) and
        // rides the SAME north-corridor trip, before the first refill checkpoint.
        if (lowerMapCorner != null && stepLayers != null && currentBatch >= 0) {
            // QUANTITATIVE future need: per-item block counts over all remaining
            // batches + closing (nothing stands yet for future batches, so full plan
            // counts). Excess beyond that need is dead weight fragmenting slots - the
            // live batch-1 stall: inventory full of prior-run drops, every item
            // "needed" by SOME future batch, zero free slots at the chest withdrawal.
            HashMap<Item, Integer> futureNeed = new HashMap<>();
            for (int fb = currentBatch; fb <= 62; fb++) {
                for (Block fbBlock : assembleBatch(fb).values()) {
                    Item fbItem = fbBlock.asItem();
                    futureNeed.put(fbItem, futureNeed.getOrDefault(fbItem, 0) + 1);
                }
            }
            for (Block cb : assembleClosing().values()) {
                Item cbItem = cb.asItem();
                futureNeed.put(cbItem, futureNeed.getOrDefault(cbItem, 0) + 1);
            }
            HashMap<Item, Integer> carried = new HashMap<>();
            HashMap<Item, ArrayList<Integer>> itemSlots = new HashMap<>();
            for (int dSlot = 0; dSlot < 36; dSlot++) {
                ItemStack dStack = mc.player.getInventory().getStack(dSlot);
                if (dStack.isEmpty() || ToolUtils.isTool(dStack) || dStack.contains(DataComponentTypes.FOOD)) continue;
                if (!materialDict.containsKey(dStack.getItem())) continue;
                Item dItem = dStack.getItem();
                carried.put(dItem, carried.getOrDefault(dItem, 0) + dStack.getCount());
                ArrayList<Integer> listForItem = itemSlots.get(dItem);
                if (listForItem == null) { listForItem = new ArrayList<>(); itemSlots.put(dItem, listForItem); }
                listForItem.add(dSlot);
            }
            HashSet<Item> unneededItems = null;
            ArrayList<Integer> dumpSlots = new ArrayList<>();
            for (Map.Entry<Item, ArrayList<Integer>> ent : itemSlots.entrySet()) {
                int need = futureNeed.getOrDefault(ent.getKey(), 0);
                int have = carried.getOrDefault(ent.getKey(), 0);
                if (have <= need) continue;   // fully used later - keep every stack
                int toDump = have - need;     // deposit the excess, newest slots first
                ArrayList<Integer> slotsOfItem = ent.getValue();
                for (int si = slotsOfItem.size() - 1; si >= 0 && toDump > 0; si--) {
                    int dSlot = slotsOfItem.get(si);
                    dumpSlots.add(dSlot);
                    toDump -= mc.player.getInventory().getStack(dSlot).getCount();
                    if (unneededItems == null) unneededItems = new HashSet<>();
                    if (!unneededItems.contains(ent.getKey())) { unneededItems.add(ent.getKey()); }
                }
            }
            if (unneededItems != null) {
                Pair<BlockPos, Vec3d> dumpChest = getBestDepositChest();
                if (dumpChest != null) {
                    depositBacklogSlots.clear();
                    depositBacklogSlots.addAll(dumpSlots);
                    StringBuilder dumpNames = new StringBuilder();
                    for (Item dn : unneededItems) { if (dumpNames.length() > 0) dumpNames.append(", "); dumpNames.append(dn.getName().getString()); }
                    info("§7Prep dump: depositing materials no future batch uses (" + dumpNames + ").");
                    int insertAt = 0;
                    for (int ci = 0; ci < checkpoints.size(); ci++) {
                        String ca = checkpoints.get(ci).getRight().getLeft();
                        if ("refill".equals(ca) || "foodChest".equals(ca)) { insertAt = ci; break; }
                    }
                    checkpoints.add(insertAt, new Pair(new Vec3d(dumpChest.getRight().x, dumpChest.getRight().y, dumpChest.getRight().z), new Pair("depositUnneeded", dumpChest.getLeft())));
                }
            }
        }
        // PICKAXE (user rule, 2026-10-20): tools are exchanged ONLY here - at the
        // pre-batch restock trip, when the best pickaxe has gone BELOW min-durability
        // (strictly below, not at the touch). Never mid-run: a worn pickaxe keeps
        // swinging until it breaks; the only mid-run trip left is fetching a
        // replacement after a pickaxe is genuinely gone.
        int pickSlot = findPickaxeSlot();
        int pickRemaining = pickSlot >= 0 ? ToolUtils.getRemainingDamage(mc.player.getInventory().getStack(pickSlot)) : 0;
        if (pickSlot < 0 || pickRemaining < minDurability.get()) {
            Item pickaxe = findRegisteredPickaxeItem();
            if (pickaxe != null) {
                info("§7Pickaxe " + (pickSlot < 0 ? "missing" : "at " + pickRemaining + " durability")
                    + " - exchanging at the batch-prep trip (min-durability " + minDurability.get() + ").");
                restockList.add(Triple.of(pickaxe, 1, 1));
                // If materials already have a chest at the head, endRestocking() will promote
                // this entry after them.  With no material restock, add its first chest now.
                if (!materialRestockQueued) addClosestRestockCheckpoint();
            }
        } else if (countPickaxes() > 1) {
            // ONE-PICKAXE INVARIANT (user rule 2026-10-20: exactly one pickaxe in the
            // inventory): the best pickaxe survives the batch, but stale spares would ride
            // along forever - queue a deposit-only pickaxe-chest visit (0 stacks = take
            // nothing, park the spares at the chest).
            Item pickaxe = findRegisteredPickaxeItem();
            if (pickaxe != null) {
                info("§7" + (countPickaxes() - 1) + " spare pickaxe(s) carried - depositing them at the batch-prep trip.");
                restockList.add(Triple.of(pickaxe, 0, 0));
                if (!materialRestockQueued) addClosestRestockCheckpoint();
            }
        }
        // BEST-TOOL PREP — AXE with the PICKAXE'S predictive rule (user rule, live
        // 2026-10-21: "same stock restock as pickaxe - never exchange or withdraw
        // until min-durability is reached"): only when the batch has axe-class blocks
        // AND the best carried axe is missing or BELOW min-durability. A worn-but-present
        // axe above threshold is KEPT (no withdrawal); below threshold it is exchanged.
        if (currentBuildPlan != null) {
            boolean needsWoodTool = false;
            for (Block b : currentBuildPlan.values()) {
                if (b.getDefaultState().isIn(BlockTags.AXE_MINEABLE)) { needsWoodTool = true; break; }
            }
            if (needsWoodTool) {
                // ONE scan pass: best remaining durability + carried count (the one-axe
                // invariant's evidence)
                int bestAxeRemaining = -1;
                int axeCarried = 0;
                for (int aSlot = 0; aSlot < 36; aSlot++) {
                    ItemStack aStack = mc.player.getInventory().getStack(aSlot);
                    if (aStack.isEmpty() || !aStack.isIn(ItemTags.AXES)) continue;
                    axeCarried++;
                    bestAxeRemaining = Math.max(bestAxeRemaining, ToolUtils.getRemainingDamage(aStack));
                }
                if (bestAxeRemaining < minDurability.get() && axeCarried == 0) {
                    ItemStack axe = null;
                    float axeSpeed = -1;
                    for (ItemStack tool : toolSet) {
                        if (!tool.isIn(ItemTags.AXES)) continue;
                        float sp = tool.getMiningSpeedMultiplier(net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
                        if (sp > axeSpeed) { axeSpeed = sp; axe = tool; }
                    }
                    if (axe != null) {
                        info("§7Axe missing - fetching one at the batch-prep trip.");
                        restockList.add(Triple.of(axe.getItem(), 1, 1));
                    }
                } else if (axeCarried > 1) {
                    // ONE-AXE INVARIANT (mirrors the pickaxe's): the best axe survives;
                    // spares ride forever otherwise. Deposit-only visit (0 stacks = take
                    // nothing) parks the spares at the chest. NEVER withdraws a new axe
                    // while any axe above min-durability is carried.
                    Item spareAxe = null;
                    float spareSpeed = -1;
                    for (ItemStack tool : toolSet) {
                        if (!tool.isIn(ItemTags.AXES)) continue;
                        float sp = tool.getMiningSpeedMultiplier(net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
                        if (sp > spareSpeed) { spareSpeed = sp; spareAxe = tool.getItem(); }
                    }
                    if (spareAxe != null && bestAxeRemaining >= minDurability.get()) {
                        info("§7" + (axeCarried - 1) + " spare axe(s) carried (best at " + bestAxeRemaining
                            + " durability, above min " + minDurability.get() + ") - depositing spares, NO new axe fetched.");
                        restockList.add(Triple.of(spareAxe, 0, 0));
                        if (!materialRestockQueued) addClosestRestockCheckpoint();
                    }
                }
            }
        }
        // FOOD (user rule): fetch ONLY when the OFFHAND stack is below 10 pieces, and
        // take only what fits in the offhand (never a second full stack on top of one).
        if (foodChest != null && offhandFoodCount() < 10) {
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
            case "buildhalf" -> {
                // SPLIT-BUILD batch 0 (creative-verified 0+0+0): "buildhalf:0:f" places
                // floor+borrow (no tops - W1@125 records the floor clean), "buildhalf:0:t"
                // places the west tops (W2@127 records them; floor frozen at 125.5+).
                // Works from ANY state - the tops half arrives while the slave waits
                // in AwaitBreakPermission2 after the floor pass.
                if (parts.length < 3) { removePendingStepCommand(); return; }
                Integer batch = parseStepBatch(parts[1]);
                if (batch == null || batch != 0) { removePendingStepCommand(); return; }
                boolean tops = "t".equals(parts[2]);
                removePendingStepCommand();
                checkpoints.clear();
                printRepairPending = false;
                breakStage = 0;
                break2Permitted = false;
                breakAllPermitted = false;
                fullSweepActive = false;
                HashMap<BlockPos, Block> half = tops
                    ? stepLayers.assembleTopsHalf(batch, lowerMapCorner)
                    : stepLayers.assembleFloorHalf(batch, lowerMapCorner);
                currentBuildPlan = half;
                currentBatch = batch;
                batchPassRetries = 0;
                placementAttempts.clear();
                collectSkips.clear();
                clearResetPending();
                sweepReArmSig = null;
                sweepWaitRounds = 0;
                sweepWaitLogged = false;
                double walkX = lowerMapCorner.getX() + 2.0 * batch + 2.5;
                if (tops) {
                    // TOPS half: MINIMAL route - no supply (materials at hand), no break2
                    // chain. Print pass from whichever side the bot is on, then wait for
                    // breakall directly. The bot just finished the floor pass, so it's
                    // already near lane 2 and materials are in inventory.
                    // FIXED CYCLE: print N->S like every batch (no mirroring)
                    double startZ = lowerMapCorner.getZ() - 2;
                    double farZ = lowerMapCorner.getZ() + 128.7;
                    checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), startZ), new Pair("batchBegin", null)));
                    checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), farZ), new Pair("printDone", null)));
                    // "awaitBreak2" (NOT "awaitBreakAll") - only this sends step:ready:N,
                    // which the master needs to start W2. The breakall handler's batch-0
                    // split path then picks the slave up from AwaitBreakPermission2.
                    checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), farZ), new Pair("awaitBreak2", null)));
                    prependLaneCentering(walkX);
                    state = State.Walking;
                    Utils.setForwardPressed(false);
                    info("§aBatch 0 split TOPS: §b" + half.size() + "§7 blocks (direct pass, no resupply).");
                } else {
                    // FLOOR half: normal batch start (supply + full print route)
                    beginBatch(batch);
                    currentBuildPlan = half;
                    info("§aBatch 0 split FLOOR: §b" + half.size() + "§7 blocks.");
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
                    // MASTER-CRASH RESYNC: the plan already exists but the master
                    // resumed batch k and re-sent the command - re-arm the route from
                    // here (idempotent: the print pass world-verifies standing blocks).
                    // Without this the slave sat in its await state consuming the
                    // command while the master waited for a ready:k that never re-fired.
                    removePendingStepCommand();
                    checkpoints.clear();
                    resumeBatchRoute(0);
                    insertNorthReEntry(0);
                    prependNorthCorridor(currentWalkLaneX());
                    state = State.Walking;
                    info("§eBatch " + batch + " re-armed by repeated build command (master resync).");
                } else if (state == State.StandBy || currentBuildPlan == null) {
                    removePendingStepCommand();
                    beginBatch(batch);
                } else if (currentBatch != batch) {
                    removePendingStepCommand();
                    info("§7Stale build:" + batch + " dropped (working batch " + currentBatch + ").");
                }
            }
            case "break2" -> {
                if (state == State.AwaitBreakPermission2) {
                    removePendingStepCommand();
                    break2Permitted = true;
                } else if (currentBuildPlan != null) {
                    // MASTER-CRASH RESYNC: break(2) is un-numbered - arm the phase
                    // directly from whatever state the slave was left in.
                    removePendingStepCommand();
                    checkpoints.clear();
                    ensurePickaxeSlot8();
                    breakStage = 2;
                    state = State.Mining;
                    resumeBatchRoute(2);
                    insertNorthReEntry(0);
                    prependNorthCorridor(currentWalkLaneX());
                    info("§eBatch " + currentBatch + " break(2) armed by command (master resync).");
                }
            }
            case "breakall" -> {
                // Batch 0 split flow: breakall arrives while the slave waits at
                // awaitBreak2 (no break(2) was ever sent). Skip straight to break-all.
                if (currentBatch == 0 && state == State.AwaitBreakPermission2) {
                    removePendingStepCommand();
                    breakAllPermitted = true;
                    checkpoints.clear();
                    startBreakAll();
                    return;
                }
                if (state == State.AwaitBreakPermissionAll) {
                    removePendingStepCommand();
                    breakAllPermitted = true;
                } else if (currentBuildPlan != null) {
                    // MASTER-CRASH RESYNC: break-all is un-numbered - arm directly.
                    removePendingStepCommand();
                    checkpoints.clear();
                    startBreakAll();
                    info("§eBatch " + currentBatch + " break-all armed by command (master resync).");
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

    /** BATCH 0, phase 1 - the floor half (recessive pairs 0+1). Identical route shape,
     *  signals and machinery to every other batch: supply -> print pass -> awaitBreak2
     *  (sends ready:0). The "prime" command is the only difference from "build:0" - it
     *  builds the floor half that W1 records before the tops exist (split-build
     *  physics). The supply run fetches the tops materials too, so the tops pass needs
     *  no refill; mid-print shortages use the emergency restock. */
    private void beginPriming() {
        if (!initialSetup()) {
            state = State.StandBy;
            return;
        }
        currentBatch = 0;
        breakStage = 0;
        printRepairPending = false;
        placementAttempts.clear();
        clearResetPending();
        // A new run kills any leftover RESET state - a stale fullSweepActive here made
        // the first mid-run supply run resurrect the dead reset ladder over the priming
        fullSweepActive = false;
        checkpoints.clear();
        state = State.Walking;

        // Lane 2 center, same as the regular batches: block 2 of the 4-lane window
        // (lanes 0-3), i.e. corner.x + 2.5 — the borrow-west lane, feet-level clean.
        double stagingX = lowerMapCorner.getX() + 2.5;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;
        HashMap<BlockPos, Block> floorPlan = assemblePriming();
        if (stepLayers != null) {
            StepCheckerLayers.addLayerAt(floorPlan, stepLayers.domLayers.get(0), lowerMapCorner, 2);   // merged batch-0 supply basis
        }
        currentBuildPlan = floorPlan;
        queueStepBuildPreparation(stagingX);
        currentBuildPlan = assemblePriming();   // the actual print plan (floor only)
        // FIXED CYCLE: print N->S like every batch (no mirroring - see beginBatch)
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), southZ), new Pair("printDone", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
        prependLaneCentering(stagingX);
        prependNorthCorridor(stagingX);
        info("§aBatch 0 floor half: §b" + currentBuildPlan.size() + "§7 blocks planned.");
    }

    /** Build the final dominant pair 63 after regular batch 62 has been recycled. */
    /** Builds the RESET ladder from (fromCol, fromSeg) in snake order - one station per
     *  (column, segment) - and appends the two-vantage final verification. */
    private void queueResetLadder(int fromCol, int fromSeg) {
        state = State.Mining;   // the ladder IS breaking — always Mining
        breakStage = 4;
        for (int col = fromCol; col <= resetLadderEndCol; col++) {
            for (int i = 0; i < RESET_SEG_START.length; i++) {
                int seg = (col % 2 == 0) ? i : RESET_SEG_START.length - 1 - i;
                if (col == fromCol && ((col % 2 == 0 && seg < fromSeg) || (col % 2 == 1 && seg > fromSeg))) continue;
                // Each pass walks from one segment boundary to the other — the entry
                // point is whichever end the bot is closer to (handled by the snake
                // direction per column). The serpentine feed breaks blocks along the way.
                // One PASS per segment: the exit end of the serpentine walk on the
                // centre lane. The pass itself clears everything in range continuously;
                // the arrival handler confirms + cleans up stragglers only.
                boolean southward = (col % 2 == 0);
                double sx = lowerMapCorner.getX() + 4.0 * col + 2.0;
                double sz = lowerMapCorner.getZ() + (southward ? RESET_SEG_END[seg] : RESET_SEG_START[seg]) + 0.5;
                BlockPos anchor = new BlockPos(lowerMapCorner.getX() + 4 * col, lowerMapCorner.getY(),
                    lowerMapCorner.getZ() + RESET_SEG_CENTER_Z[seg]);
                checkpoints.add(new Pair(new Vec3d(sx, lowerMapCorner.getY(), sz), new Pair("resetPass", anchor)));
            }
        }
        // Reset finish: deposit + park at the north staging point
        queueVerifyWalk();
    }

    /** Appends the RESET FINISH (user rule, 2026-10-20): NO verify-walk stations -
     *  the ladder's per-segment gates already confirm every segment clean (census with
     *  the bounce margins) before it advances, so a separate verification walk is
     *  redundant checkpoints. After the ladder: deposit the collected blocks, then the
     *  slave PARKS at the north staging point where every run starts. Exit north via
     *  the corridor (never a diagonal beeline across the box). */
    private void queueVerifyWalk() {
        // The finish block is APPENDED with its own corridor legs INSIDE it (live-
        // caught 2026-10-20: a prepend here ran at LADDER-BUILD time - its legs led
        // the whole queue and were consumed at the reset START, so when the ladder
        // finished at the SOUTH end the bare deposit checkpoint was next and the bot
        // beelined diagonally to the north chest, queue=2 in the log).
        Pair<BlockPos, Vec3d> doneChest = getBestDepositChest();
        double corridorZ = lowerMapCorner.getZ() - 2;
        double y = lowerMapCorner.getY();
        if (doneChest != null) {
            double lastLadderX = checkpoints.isEmpty() ? lowerMapCorner.getX() + 2.5 : checkpoints.getLast().getLeft().x;
            checkpoints.add(new Pair(new Vec3d(lastLadderX, y, corridorZ), new Pair("sprint", null)));       // exit north on the final column
            checkpoints.add(new Pair(new Vec3d(doneChest.getRight().x, y, corridorZ), new Pair("sprint", null)));   // corridor to the chest
            checkpoints.add(new Pair(doneChest.getRight(), new Pair("depositBuilding", doneChest.getLeft())));
        }
        Vec3d park = new Vec3d(lowerMapCorner.getX() + 2.5, y, corridorZ);
        checkpoints.add(new Pair(park, new Pair("fullSweepDone", null)));
    }

    
    
    private int resetZToSeg(int relZ) {
        for (int seg = RESET_SEG_START.length - 1; seg >= 0; seg--) if (relZ >= RESET_SEG_START[seg]) return seg;
        return 0;
    }

    /** Nearest non-air block inside a bounded column-segment area of the print box. */
    private BlockPos findNearestBlockInArea(int x0, int x1, int z0, int z1) {
        if (mc.world == null || mc.player == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3d eye = mc.player.getEyePos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = lowerMapCorner.getY(); y <= lowerMapCorner.getY() + 3; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (miningBlockState(pos).isAir()) continue;
                    double dist = eye.distanceTo(pos.toCenterPos());
                    if (dist < bestDist) { bestDist = dist; best = pos; }
                }
            }
        }
        return best;
    }

    /** Nearest pickup-able ground item inside a bounded column-segment area. */
    private ItemEntity findNearestItemInArea(int x0, int x1, int z0, int z1) {
        if (mc.world == null || mc.player == null) return null;
        ItemEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (itemEntity.getX() < x0 || itemEntity.getX() > x1 + 1) continue;
            if (itemEntity.getZ() < z0 || itemEntity.getZ() > z1 + 1) continue;
            if (itemEntity.getY() < lowerMapCorner.getY() - 3 || itemEntity.getY() > lowerMapCorner.getY() + 5) continue;
            double dist = mc.player.getEyePos().distanceTo(itemEntity.getEntityPos());
            if (dist < nearestDist) { nearestDist = dist; nearest = itemEntity; }
        }
        return nearest;
    }

    /** Ground-collided, pickup-able, unowned drop - the ONE filter every item scanner
     *  shares. (itemCensusInArea deliberately splits these states for its census.) */
    private boolean isPickupable(ItemEntity itemEntity) {
        return itemEntity.groundCollision && !itemEntity.cannotPickup() && itemEntity.getOwner() == null;
    }

    /** RESET verification logging: counts EVERY ItemEntity in a bounded area, split by
     *  the pickup filters findNearestItemInArea applies. ready = would be targeted now;
     *  falling / pickupDelay / owned = invisible to the CLEAN check at this instant.
     *  A CLEAN verdict with falling+pickupDelay > 0 means the segment was declared clean
     *  while drops from the final breaks were still in flight (they get left behind). */
    private int[] itemCensusInArea(int x0, int x1, int z0, int z1) {
        int[] census = new int[4];   // {ready, falling, pickupDelay, owned}
        if (mc.world == null) return census;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getX() < x0 || itemEntity.getX() > x1 + 1) continue;
            if (itemEntity.getZ() < z0 || itemEntity.getZ() > z1 + 1) continue;
            if (itemEntity.getY() < lowerMapCorner.getY() - 3 || itemEntity.getY() > lowerMapCorner.getY() + 5) continue;
            if (itemEntity.getOwner() != null) census[3]++;
            else if (!itemEntity.groundCollision) census[1]++;
            else if (itemEntity.cannotPickup()) census[2]++;
            else census[0]++;
        }
        return census;
    }

    /** RESET verification logging: break-confirmations still in flight inside the
     *  window (fastBreak sent, server block-update not yet received). */
    private int countPendingInRange(int x0, int x1, int z0, int z1) {
        int n = 0;
        for (BlockPos pos : breakPending.keySet()) {
            if (pos.getX() >= x0 && pos.getX() <= x1 && pos.getZ() >= z0 && pos.getZ() <= z1) n++;
        }
        return n;
    }

    /** RESET verification logging: non-air blocks (all print-box layers) inside the window. */
    private int countBlocksInArea(int x0, int x1, int z0, int z1) {
        if (mc.world == null || lowerMapCorner == null) return -1;
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = lowerMapCorner.getY(); y <= lowerMapCorner.getY() + 3; y++) {
                    if (!miningBlockState(new BlockPos(x, y, z)).isAir()) n++;
                }
            }
        }
        return n;
    }

    /** RESET verification logging: blocks flagged for multi-tick vanilla breaking inside the window. */
    private int countVanillaInRange(int x0, int x1, int z0, int z1) {
        int n = 0;
        for (BlockPos pos : needsVanillaBreak) {
            if (pos.getX() >= x0 && pos.getX() <= x1 && pos.getZ() >= z0 && pos.getZ() <= z1) n++;
        }
        return n;
    }

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
        nextBlockPos = null;
        vanillaBreakRetries.clear();
        jamWatchTarget = null;
        jamWatchTicks = 0;
        breakSweepActedThisTick = false;
        resetStallSig = null;
        resetStallRounds = 0;
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        sweepReArmSig = null;
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
        // Checkpoint-batch ladder SCOPED to what was actually printed: the batch being
        // built (or the last one) plus one safety column west for its borrow leftovers.
        // One batch window = exactly one 4-lane ladder column. The two-vantage verify
        // after the ladder still covers the whole box as the backstop for older strays.
        // PROGRESS-DRIVEN SCOPE (user rule, 2026-10-20): only the PRINTED batches are
        // cleared - the ladder AND the verify walk stay inside them (+1 safety col
        // east for borrow/drop bounce). Sources: the live in-memory batch (a running
        // flow) and the shared progress file (a crashed/restarted run - batches 0..k
        // printed = ladder cols 0..k). No whole-map walks or scans.
        int progressBatch = master.loadStepProgress();   // shared _configs file; -1-safe on the slave
        int liveBatch = currentBatch >= 0 ? currentBatch : 0;
        int scopeCol = Math.min(31, Math.max(liveBatch, Math.max(0, progressBatch)) + 1);
        resetLadderEndCol = scopeCol;
        resetLadderCol = 0;   // batches print west->east: everything up to the scope col
        resetPendingScope = scopeCol;
        saveResetPending(scopeCol);   // survive disable/crash: re-enable resumes the reset
        info("§7RESET scoped to cols 0-" + scopeCol + " (live batch " + liveBatch
            + ", progress batch " + progressBatch + ") - no whole-map pass.");
        // Start from the segment closest to the bot's current position (not always 0):
        // the bot might be at the south end after a print pass — walking to segment 0
        // (north) wastes transit time. Snake direction adapts per column anyway.
        int botRelZ = mc.player != null ? mc.player.getBlockZ() - lowerMapCorner.getZ() : 0;
        resetLadderSeg = botRelZ > 64 ? 4 : (botRelZ > 26 ? 2 : 0);   // nearest of {0, 2, 4}
        queueResetLadder(resetLadderCol, 0);

        // Route via the north corridor (never cross the map area diagonally)
        double corridorZ = lowerMapCorner.getZ() - 2;
        if (Math.abs(mc.player.getZ() - corridorZ) > 1.5) {
            checkpoints.add(0, new Pair(new Vec3d(mc.player.getX(), lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        }

        state = State.Mining;   // Mining walks AND breaks: the serpentine pass clears as it goes
        breakStage = 4;
        fullSweepActive = true;
        sweepBudget = 600;
        info("§7RESET ladder (scoped to cols " + resetLadderCol + "-" + resetLadderEndCol
            + "): serpentine passes, each segment confirmed fully clean before the next.");
    }

    /**
     * Scans the print box (128 lanes x 128 rows x y99-102) for the nearest non-air block
     * from the bot's current position. Only checks loaded chunks (render distance).
     */
    private BlockPos findNearestPrintBoxBlock() {
        return findNearestPrintBoxBlock(Collections.emptySet());
    }

    /** Exclusion-set variant for the distinct-position multi-sender. */
    private BlockPos findNearestPrintBoxBlock(Set<BlockPos> exclude) {
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
                    // Only check blocks inside the print box (+ the noobline column one
                    // block north — it is printed and recycled with its batch, and a
                    // leftover noobline block walls the north corridor entry)
                    if (x < lowerMapCorner.getX() || x >= lowerMapCorner.getX() + 128) continue;
                    if (z < lowerMapCorner.getZ() - 1 || z >= lowerMapCorner.getZ() + 128) continue;
                    for (int y = y0; y <= y1; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!miningBlockState(pos).isAir() && !exclude.contains(pos)) {
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

    /** Count of plan blocks still standing (not air) in the live world. */
    private int countRemainingPlanBlocks() {
        if (currentBuildPlan == null) return 0;
        int count = 0;
        for (BlockPos pos : currentBuildPlan.keySet()) {
            if (!miningBlockState(pos).isAir()) count++;
        }
        return count;
    }

    /** Collection-run target: the nearest ready drop in the batch window AT/AHEAD of
     *  the bot (z never more than 1.5 blocks behind) so the run advances monotonically
     *  south -> north. Falls back to the nearest drop anywhere if everything left is
     *  behind (e.g. an interrupted run). A drop re-picked 10x without disappearing is
     *  blacklisted for this batch (unreachable) and skipped. */
    private ItemEntity nearestCollectTarget() {
        if (mc.world == null || mc.player == null) return null;
        int westX = batchWindowWestX();
        ItemEntity best = null, bestAny = null;
        double bestDist = Double.MAX_VALUE, bestAnyDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (itemEntity.getX() < westX - 4 || itemEntity.getX() > westX + 7) continue;
            if (itemEntity.getZ() < lowerMapCorner.getZ() - 3 || itemEntity.getZ() > lowerMapCorner.getZ() + 129) continue;
            if (itemEntity.getY() < lowerMapCorner.getY() - 3 || itemEntity.getY() > lowerMapCorner.getY() + 5) continue;
            BlockPos key = BlockPos.ofFloored(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ());
            if (collectSkips.getOrDefault(key, 0) >= 10) continue;   // blacklisted this batch
            double dist = mc.player.getEyePos().distanceTo(itemEntity.getEntityPos());
            if (dist < bestAnyDist) { bestAnyDist = dist; bestAny = itemEntity; }
            if (itemEntity.getZ() >= mc.player.getZ() - 1.5 && dist < bestDist) { bestDist = dist; best = itemEntity; }
        }
        if (best != null) {
            BlockPos key = BlockPos.ofFloored(best.getX(), best.getY(), best.getZ());
            collectSkips.merge(key, 1, Integer::sum);
        }
        return best != null ? best : bestAny;
    }

    /** Nearest still-standing plan block to the bot (break-all straggler chase target). */
    private BlockPos nearestRemainingPlanBlock() {
        if (currentBuildPlan == null || mc.player == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : currentBuildPlan.keySet()) {
            if (miningBlockState(pos).isAir()) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist < bestDist) { bestDist = dist; best = pos; }
        }
        return best;
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

        // Closing = dominant pair 63 on lanes 126-127 (2-lane window): the slave walks
        // the second lane's center (block 127, y2-only above the head), NOT the master's
        // x=253 sweep lane
        double stagingX = lowerMapCorner.getX() + 127.5;
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;
        queueStepBuildPreparation(stagingX);
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), southZ), new Pair("stepClosingReady", null)));
        checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("stepClosingWait", null)));
        prependLaneCentering(stagingX);
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
            master.stepMasterStage = StepMasterStage.ABORTED;
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
        // When a lane-centering leg was just queued, the exit must run north on the
        // lane-2 centerline (its x) - not from the bot's current off-lane x
        double exitX = mc.player.getX();
        if (!checkpoints.isEmpty() && "centerLane".equals(checkpoints.getFirst().getRight().getLeft())) {
            exitX = checkpoints.getFirst().getLeft().x;
        }
        checkpoints.addFirst(new Pair(new Vec3d(targetX, lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        if (Math.abs(mc.player.getZ() - corridorZ) > 1.5) {
            checkpoints.addFirst(new Pair(new Vec3d(exitX, lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        }
    }

    // --- Dynamic transit speed (Meteor Speed module, never copied) ---

    /** Dynamic sprint mode: transit legs (corridor, supply trips, waits between passes)
     *  ride Meteor's Speed module - vanilla mode, 7.192 b/s, timer 1 - while printing
     *  and breaking always run at the default walk speed. Toggled from our tick; the
     *  module is force-disabled whenever we stop using it (incl. onDeactivate/abort). */
    private boolean transitSpeedOn = false;

    private void updateTransitSpeed() {
        if (sprinting.get() != SprintMode.Dynamic) {
            if (transitSpeedOn) setTransitSpeed(false);
            return;
        }
        boolean printing = state == State.Walking && breakStage == 0 && currentBuildPlan != null
            && !checkpoints.isEmpty() && ALLOW_PLACE_ACTIONS.contains(checkpoints.getFirst().getRight().getLeft());
        // BREAK PASSES NEVER SPEED-HACK (live 2026-10-21: at 7.12 bps the bot outruns
        // the server's echo AND reach — breaks expire out-of-reach into a frozen vanilla
        // queue (51 blocks stuck) and the server rubber-bands the movement itself. The
        // math cap is 30 bps / 5.25 blocks-per-row = 5.7 bps max useful pace; sprint
        // (~5.6) sits just under it).
        boolean breaking = state == State.Mining || (breakStage != 0 && state != State.StandBy);
        boolean want = !printing && !breaking && state != State.StandBy;
        if (want != transitSpeedOn) setTransitSpeed(want);
    }

    private void setTransitSpeed(boolean on) {
        try {
            Speed speed = Modules.get().get(Speed.class);
            if (on) {
                speed.speedMode.set(SpeedModes.Vanilla);
                speed.vanillaSpeed.set(7.192);
                speed.timer.set(1.0);
                if (!speed.isActive()) speed.toggle();
            } else if (speed.isActive()) {
                speed.toggle();   // onDeactivate clears the Timer override
            }
        } catch (Exception e) {
            warning("Speed module control failed: " + e.getMessage());
        }
        transitSpeedOn = on;
    }

    /** Direction-agnostic passes: the slave starts a print/break route from whichever
     *  end of the window it is already on (south half -> south-first mirrored route). */
    /** The walking lane (lane 2 center) of the phase the slave is in: batch k, priming,
     *  or the closing build. Placement is gated on standing centered here. */
    private double currentWalkLaneX() {
        if (currentBatch == -1) return lowerMapCorner.getX() + 2.5;
        if (currentBatch == 63) return lowerMapCorner.getX() + 127.5;
        return lowerMapCorner.getX() + 2.0 * currentBatch + 2.5;
    }

    /** Before a new phase prints, make sure the slave is ON lane 2: if the previous
     *  phase (sweep/repair leftovers) left it off the centerline, queue a lateral move
     *  onto it first. Placement stays suppressed until centered (both via the
     *  centerLane action not allowing placement and the picker's lane gate), so the
     *  new batch's floor blocks can never be placed into the bot's own path. */
    private void prependLaneCentering(double laneX) {
        if (Math.abs(mc.player.getX() - laneX) > 0.3) {
            checkpoints.addFirst(new Pair(new Vec3d(laneX, lowerMapCorner.getY(), mc.player.getZ()), new Pair("centerLane", null)));
        }
    }

    /** Placement check at the south end of the print pass. Missing blocks are NOT retried
     *  here - the repair path places them from lane -1 while walking north (different
     *  vantage), then one more lane 2 pass, before the break(2) wait. */
    private void verifyPrintPass() {
        int[] defects = planDefects();
        if (defects[0] == 0 && defects[1] == 0) {
            info("§aBatch " + currentBatch + " printed: §b" + currentBuildPlan.size() + "§7 blocks placed (world-verified).");
            return;
        }
        printRepairPending = true;
        info("§eBatch " + currentBatch + " print has §c" + defects[0] + "§e missing / §c" + defects[1] + "§e wrong - repairing from lane -1 on the way north, then one more lane 2 pass.");
    }

    /** Strict world-truth gate used by priming and closing, where a repair route is undefined. */
    private boolean verifyCurrentPlan(String label) {
        if (currentBuildPlan == null) {
            error(label + " has no active placement plan.");
            state = State.StandBy;
            return false;
        }
        int[] defects = planDefects();
        if (defects[0] == 0 && defects[1] == 0) return true;
        error(label + " placement incomplete: " + defects[0] + " missing, " + defects[1] + " wrong. Stopped.");
        state = State.StandBy;
        Utils.setForwardPressed(false);
        return false;
    }

    /** Strict check after the repair passes: proceed or stop for diagnosis. */
    private boolean verifyPrintPass2() {
        int[] defects = planDefects();
        if (defects[0] == 0 && defects[1] == 0) {
            info("§aBatch " + currentBatch + " repair complete - plan fully placed.");
            return true;
        }
        error("Batch " + currentBatch + " print still incomplete after the repair passes: " + defects[0] + " missing, " + defects[1] + " wrong. Stopped for diagnosis.");
        state = State.StandBy;
        Utils.setForwardPressed(false);
        return false;
    }

    /** {missing, wrong} counts of the active plan against the live world - the ONE
     *  shared scan behind every placement-verification gate (pre-dedup copies drifted
     *  and could disagree about the same pass). */
    private int[] planDefects() {
        int missing = 0;
        int wrong = 0;
        for (Map.Entry<BlockPos, Block> entry : currentBuildPlan.entrySet()) {
            BlockState worldState = miningBlockState(entry.getKey());
            if (worldState.isAir()) missing++;
            else if (!worldState.getBlock().equals(entry.getValue())) wrong++;
        }
        return new int[]{missing, wrong};
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
        info("§eBatch " + currentBatch + " break(2) leftover (" + remaining.size() + ") - fixed-direction retry pass (S->N).");
        double lane2X = lowerMapCorner.getX() + 2.0 * currentBatch + 2.5;
        checkpoints.add(new Pair(new Vec3d(lane2X, lowerMapCorner.getY(), lowerMapCorner.getZ() + 128.7), new Pair("sprint", null)));
        checkpoints.add(new Pair(new Vec3d(lane2X, lowerMapCorner.getY(), lowerMapCorner.getZ() - 2), new Pair("break2Done", null)));
        state = State.Mining;
        return false;
    }

    /** On the master's break-all call: deposit every building block first (north corridor
     *  trip), then break the whole window from lane 2 north -> south, collect everything,
     *  and exit north along lane 2 picking up any leftovers. */
    private void startBreakAll() {
        breakStage = 4;
        checkpoints.clear();
        double walkX = lowerMapCorner.getX() + 2.0 * currentBatch + 2.5;
        // ALWAYS north -> south: the pass must END at the south end so the single
        // south-to-north collection run starts exactly where the bot stands.
        double startZ = lowerMapCorner.getZ() - 2;
        double farZ = lowerMapCorner.getZ() + 128.7;

        Pair<BlockPos, Vec3d> chest = getBestDepositChest();
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), startZ), new Pair("breakAllBegin", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), farZ), new Pair("breakAllDone", null)));
        checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), farZ), new Pair("batchExit", null)));
        if (chest != null) {
            checkpoints.addFirst(new Pair(chest.getRight(), new Pair("depositBuilding", chest.getLeft())));
            prependNorthCorridor(chest.getRight().x);
        } else {
            prependNorthCorridor(walkX);
        }
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

    /** Queue the given chest at the HEAD of the route as the next deposit stop. */
    private void queueDepositChestAtHead(Pair<BlockPos, Vec3d> chest) {
        checkpoints.add(0, new Pair(chest.getRight(), new Pair("depositBuilding", chest.getLeft())));
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
    /** Emergency-trip RETURN discipline: after a chest visit the resumed route must be
     *  re-entered through the NORTH checkpoint - corridor back to the batch lane, then
     *  south along the lane. Without this, the bot beelined diagonally from the north
     *  chest straight across the printed box to the route's first (mid/south) position
     *  (user callout, live 2026-10-20). */
    private void insertNorthReEntry(int index) {
        checkpoints.add(Math.max(0, Math.min(index, checkpoints.size())), new Pair(
            new Vec3d(currentWalkLaneX(), lowerMapCorner.getY(), lowerMapCorner.getZ() - 2), new Pair("sprint", null)));
    }

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
            refillFromCurrentPlan();
        }
        if (needPickaxe && !hasAnyPickaxe() && !toolSet.isEmpty()) {
            for (ItemStack tool : toolSet) {
                if (tool.isIn(ItemTags.PICKAXES)) {
                    restockList.add(Triple.of(tool.getItem(), 1, 1));
                    // Without a chest at the head of the queue the trip never visits one:
                    // the emergency "restock" walked the corridor, resumed breaking with
                    // the same worn pickaxe and re-triggered every cooldown (observed loop)
                    if (restockList.size() == 1) addClosestRestockCheckpoint();
                    break;
                }
            }
        }
        if (fullSweepActive) {
            // RESET interrupted by the emergency: resume the LADDER at the current
            // station - never a batch route
            checkpoints.clear();
            if (resetLadderCol < 32) {
                queueResetLadder(resetLadderCol, resetLadderSeg);
            } else {
                queueVerifyWalk();
            }
        } else {
            int chestSectionSize = checkpoints.size();   // chest trip queued above
            resumeBatchRoute(savedStage);                // route appended at the tail
            // Re-entry BETWEEN chest and route: corridor back to the batch lane's
            // north staging, then south along the lane (head-insertion landed it
            // BEFORE the chest visit and the return leg stayed a diagonal beeline -
            // live 2026-10-20 mid-print emergency restock).
            insertNorthReEntry(chestSectionSize);
        }
        // Exit north via the corridor (head legs)
        prependNorthCorridor(checkpoints.isEmpty() ? lowerMapCorner.getX() + 2.5 : checkpoints.getFirst().getLeft().x);
        state = State.Walking;
        Utils.setForwardPressed(false);
    }

    /** Rebuilds the remaining route after an interruption (supply run / deposit trip).  The
     *  2k lane formula applies to every batch (batch 0 included - its floor half uses
     *  the same route); closing (63) keeps its own staging lane and names. */
    private void resumeBatchRoute(int stage) {
        double northZ = lowerMapCorner.getZ() - 2;
        double southZ = lowerMapCorner.getZ() + 128.7;
        if (currentBatch == 63) {
            if (stage == 4) {
                double sweepX = lowerMapCorner.getX() + 253.5;
                checkpoints.add(new Pair(new Vec3d(sweepX, lowerMapCorner.getY(), northZ), new Pair("breakAllBegin", null)));
                checkpoints.add(new Pair(new Vec3d(sweepX, lowerMapCorner.getY(), southZ), new Pair("breakAllDone", null)));
                checkpoints.add(new Pair(new Vec3d(sweepX, lowerMapCorner.getY(), northZ), new Pair("stepClosingDone", null)));
            } else {
                double stagingX = lowerMapCorner.getX() + 127.5;
                checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
                checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), southZ), new Pair("stepClosingReady", null)));
                checkpoints.add(new Pair(new Vec3d(stagingX, lowerMapCorner.getY(), northZ), new Pair("stepClosingWait", null)));
            }
            return;
        }
        // FIXED CYCLE on resume too: the same directions as a fresh batch, so an
        // emergency interrupt can never flip the parity into dead NS walks.
        double walkX = lowerMapCorner.getX() + 2.0 * currentBatch + 2.5;
        switch (stage) {
            case 0 -> {
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("printDone", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
            }
            case 2 -> {
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
            }
            default -> {
                // break-all: N->S (forced); the batchExit queued here is stale by
                // design - the collection run replaces it with an in-place exit.
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), northZ), new Pair("breakAllBegin", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("breakAllDone", null)));
                checkpoints.add(new Pair(new Vec3d(walkX, lowerMapCorner.getY(), southZ), new Pair("batchExit", null)));
            }
        }
    }

    /** Inventory filled up mid-break: deposit trip through the north corridor, then resume. */
    private void pauseBreakAllForDeposit() {
        info("§eInventory full - depositing building blocks before continuing the break.");
        breakStage = 0;
        checkpoints.clear();
        // Explicit order: chest visit -> north re-entry on the batch lane -> resumed
        // route; exit legs prepended last (north on the lane, then the corridor).
        Pair<BlockPos, Vec3d> chest = getBestDepositChest();
        if (chest != null) {
            checkpoints.add(new Pair(chest.getRight(), new Pair("depositBuilding", chest.getLeft())));
        }
        insertNorthReEntry(checkpoints.size());
        resumeBatchRoute(4);
        prependNorthCorridor(chest != null ? chest.getRight().x : currentWalkLaneX());
        state = State.Walking;
    }

    /** Final check at the north exit: everything recycled, pickaxe back to the offhand.
     *  Same verification logic as the RESET completion gate: the census sees drops the
     *  filtered scans cannot (falling / pickup-delayed), in-flight drops are DWELT for
     *  instead of abandoned, a full inventory deposits before re-verifying, and re-arm
     *  retries are only consumed when the sweep state is FROZEN (no progress). */
    private void finishBatch() {
        // COMBINED SWEEP: check both remaining blocks and ground items at once.
        // sweepTick handles whichever is nearest (block → break, item → collect).
        // No separate retry walks, no zigzag — one spatial-awareness pass.
        int remainingBlocks = countRemainingPlanBlocks();
        int groundItems = countGroundItemsInWindow();
        int[] census = itemCensusInBatchWindow();
        int pendBrk = countPendingInRange(batchWindowWestX() - 2, batchWindowWestX() + 5,
            lowerMapCorner.getZ() - 3, lowerMapCorner.getZ() + 129);
        int inFlight = census[1] + census[2];
        if (remainingBlocks == 0 && groundItems == 0 && census[0] == 0) {
            if (inFlight > 0 && sweepWaitRounds < 100) {
                // WAIT for in-flight drops instead of completing: they land and become
                // pickup-able within ~10-20 ticks, and the next batch's window may not
                // cover them. Bounded at 100 rounds; landing flips groundItems > 0 and
                // takes the re-arm path below.
                sweepWaitRounds++;
                if (!sweepWaitLogged) {
                    info("§6[SWEEP-VERIFY]§7 batch " + currentBatch + ": clean but " + census[1]
                        + " falling + " + census[2] + " pickup-delayed drops in flight - waiting."
                        + " pendBrk=" + pendBrk);
                    sweepWaitLogged = true;
                }
                checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
                state = State.Walking;
                return;
            }
            if (inFlight > 0) {
                warning("§6[SWEEP-VERIFY]§e batch " + currentBatch + ": drops still in flight after 100 wait rounds"
                    + " (falling=" + census[1] + " pickupDelay=" + census[2] + ") - completing anyway.");
            }
            sweepWaitRounds = 0;
            sweepWaitLogged = false;
            sweepReArmSig = null;
            info("§6[SWEEP-VERIFY]§7 batch " + currentBatch + " gate: blocks=0 items=0 census[ready="
                + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2] + " owned=" + census[3]
                + "] pendBrk=" + pendBrk + " vanillaQ=" + needsVanillaBreak.size());
            info("§aBatch " + currentBatch + " COMPLETE: printed and fully recycled.");
            int finishedBatch = currentBatch;
            currentBuildPlan = null;
            breakStage = 0;
            state = State.StandBy;
            Utils.setForwardPressed(false);
            SlaveSystem.sendMessageToMaster("step:done:" + finishedBatch);
            return;
        }
        // Inventory full with content remaining: deposit first, then re-verify —
        // otherwise every sweepTick re-arm exits instantly and burns all retries.
        if (freeInventoryCapacity() == 0 && (groundItems > 0 || inFlight > 0)) {
            Pair<BlockPos, Vec3d> depositChest = getBestDepositChest();
            if (depositChest != null) {
                checkpoints.clear();
                checkpoints.add(new Pair(depositChest.getRight(), new Pair("depositBuilding", depositChest.getLeft())));
                checkpoints.add(new Pair(depositChest.getRight(), new Pair("batchExit", null)));
                prependNorthCorridor(depositChest.getRight().x);
                state = State.Mining;
                info("§6[SWEEP-VERIFY]§7 batch " + currentBatch + ": inventory full with "
                    + (groundItems + inFlight) + " drops pending - depositing, then re-verifying.");
                return;
            }
        }
        // Re-arm the sweep. PROGRESS-AWARE: a retry is only consumed when the sweep
        // state (blocks + item states + position) is unchanged since the last re-arm —
        // a sweep that is still breaking/collecting re-arms freely instead of racing
        // a blind 8-cap to a force-complete with content standing.
        String reArmSig = remainingBlocks + "|" + census[0] + "," + census[1] + "," + census[2] + "," + census[3]
            + "|p" + mc.player.getBlockX() + "," + mc.player.getBlockZ();
        boolean frozen = reArmSig.equals(sweepReArmSig);
        sweepReArmSig = reArmSig;
        if (frozen) batchPassRetries++;
        else batchPassRetries = Math.max(0, batchPassRetries - 1);
        if (remainingBlocks > 0 || groundItems > 0 || inFlight > 0) {
            if (batchPassRetries < 8) {
                sweepBudget = 200;   // ~10 seconds of combined sweep
                checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("sweepTick", null)));
                state = State.Walking;
                info("§6[SWEEP-VERIFY]§7 batch " + currentBatch + " SWEEP: blocks=" + remainingBlocks
                    + " items[ready=" + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2]
                    + " owned=" + census[3] + "] pendBrk=" + pendBrk
                    + " vanillaQ=" + needsVanillaBreak.size()
                    + " - re-arm " + (frozen ? "(FROZEN) " : "") + "retry " + batchPassRetries + "/8.");
                return;
            }
            warning("§6[SWEEP-VERIFY]§c batch " + currentBatch + ": sweep FROZEN after " + batchPassRetries
                + " re-arms with blocks=" + remainingBlocks + " items ready=" + census[0]
                + " - completing with content remaining (PERSISTENT REJECTION logs should name the culprit).");
        }
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        sweepReArmSig = null;
        info("§aBatch " + currentBatch + " COMPLETE: printed and fully recycled.");
        int doneBatch = currentBatch;
        currentBuildPlan = null;
        breakStage = 0;
        state = State.StandBy;
        Utils.setForwardPressed(false);
        SlaveSystem.sendMessageToMaster("step:done:" + doneBatch);
    }

    /** Batch-window item census {ready, falling, pickupDelay, owned} — same states the
     *  RESET census uses, over the batch's recycling window incl. its item margin. */
    private int[] itemCensusInBatchWindow() {
        int westX = batchWindowWestX();
        return itemCensusInArea(westX - 4, westX + 6, lowerMapCorner.getZ() - 3, lowerMapCorner.getZ() + 129);
    }

    /** West lane x of the current batch's 4-lane recycling window (world coords). */
    private int batchWindowWestX() {
        return lowerMapCorner.getX() + 2 * Math.max(currentBatch, 0);
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
            if (!isPickupable(itemEntity)) continue;
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
        int westX = batchWindowWestX();
        int count = 0;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (itemEntity.getX() < westX - 4 || itemEntity.getX() > westX + 7) continue;
            if (itemEntity.getZ() < lowerMapCorner.getZ() - 3 || itemEntity.getZ() > lowerMapCorner.getZ() + 130) continue;
            if (itemEntity.getY() < lowerMapCorner.getY() - 2 || itemEntity.getY() > lowerMapCorner.getY() + 5) continue;
            count++;
        }
        return count;
    }

    /** Nearest unplaced plan block within reach that does not intersect the player's body. */
    private BlockPos getNextBatchPlacementPos() {
        return getNextBatchPlacementPos(Collections.emptySet());
    }

    /** Exclusion-set variant: nearest eligible position NOT in `exclude` (multi-place). */
    private BlockPos getNextBatchPlacementPos(Set<BlockPos> exclude) {
        if (currentBuildPlan == null) return null;
        // LANE GATE: no printing unless the bot stands centered on the phase's lane-2
        // centerline. Off-center (e.g. after a sweep left it straddling lanes), freshly
        // placed floor blocks on the west lanes can catch the bot's side and wall it in.
        if (lowerMapCorner != null && Math.abs(mc.player.getX() - currentWalkLaneX()) > 0.3) return null;
        // Litematica-style: any plan block is placed the moment it enters interaction
        // range — ahead, beside, or behind. The walking lane (2, the east borrow lane)
        // only carries y2 blocks above the head, so nothing is ever placed at feet level
        // on the bot's path; blocks intersecting its body are retried once it moves on.
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : currentBuildPlan.keySet()) {
            if (!miningBlockState(pos).isAir()) continue;
            if (exclude.contains(pos)) continue;
            // Skip positions on placement cooldown (litematica-style: don't hammer the
            // same failed block — let the timeout expire, then retry)
            if (placementCooldowns.getOrDefault(pos, 0) > 0) continue;
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
        return getNextBatchBreakPos(Collections.emptySet());
    }

    /** Exclusion-set variant: returns the nearest eligible target NOT in `exclude` —
     *  lets sendDistinctBreaks pick several DIFFERENT positions per tick. */
    private BlockPos getNextBatchBreakPos(Set<BlockPos> exclude) {
        if (currentBuildPlan == null) return null;
        boolean break2Only = breakStage == 2;
        int westX = lowerMapCorner.getX() + 2 * currentBatch;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : currentBuildPlan.keySet()) {
            if (miningBlockState(pos).isAir()) continue;
            if (exclude.contains(pos)) continue;
            if (breakPending.containsKey(pos)) continue;   // awaiting server confirm - not sendable
            if (break2Only && (pos.getX() >= westX + 2 || pos.getY() < lowerMapCorner.getY() + 2)) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist > interactionRange.get()) continue;
            if (dist < bestDist) { bestDist = dist; best = pos; }
        }
        return best;
    }

    /** Pieces of food currently in the OFFHAND stack (0 if it holds something else). */
    private int offhandFoodCount() {
        ItemStack off = mc.player.getOffHandStack();
        if (off.isEmpty() || !off.contains(DataComponentTypes.FOOD)) return 0;
        return off.getCount();
    }

    /** Finds the pickaxe with the HIGHEST remaining durability in the inventory (no other
     *  tool qualifies); -1 if none. Best-first, not first-found: the prep check must
     *  measure the best pickaxe and slot 8 must arm the freshest one - first-found armed
     *  stale pickaxes while fresh ones sat deeper in the inventory, re-triggering a fetch
     *  every batch prep (live 2026-10-20: five pickaxes accumulated). */
    private int findPickaxeSlot() {
        int bestSlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.PICKAXES)) continue;
            if (bestSlot == -1 || ToolUtils.getRemainingDamage(stack) > ToolUtils.getRemainingDamage(mc.player.getInventory().getStack(bestSlot))) bestSlot = slot;
        }
        return bestSlot;
    }

    private int countPickaxes() {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.PICKAXES)) count++;
        }
        return count;
    }

    /** True when the chest currently being interacted with is registered as stocking a
     *  pickaxe - the only chest a used pickaxe may be parked into. */
    private boolean isOpenChestAPickaxeChest() {
        if (lastInteractedChest == null) return false;
        for (HashMap.Entry<Item, ArrayList<Pair<BlockPos, Vec3d>>> entry : materialDict.entrySet()) {
            if (!new ItemStack(entry.getKey()).isIn(ItemTags.PICKAXES)) continue;
            if (containsChest(entry.getValue(), lastInteractedChest)) return true;
        }
        return false;
    }

    /** True when ANY pickaxe is carried (slot 8 or anywhere in the inventory) - worn
     *  ones count too: exchanges happen only at the pre-batch restock, so mid-run a
     *  worn pickaxe is still the tool in hand (user rule, 2026-10-20). */
    private boolean hasAnyPickaxe() {
        if (mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) return true;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.PICKAXES)) return true;
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
        if (chest.getLeft() == null || chest.getLeft().equals(BlockPos.ORIGIN)) {
            warning("No chest stocks " + pickaxe.getName().getString() + " - cannot fetch a pickaxe.");
            return;
        }
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
        //Calculate the next items to restock: count one per still-missing plan block
        //(live world truth), capped by the available inventory slots
        HashMap<Item, Integer> requiredItems = new HashMap<>();
        if (currentBuildPlan == null) return requiredItems;
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

    /** Working interval bookkeeping (swapIntoHotbar's eviction heuristic reads it). */
    private void setInterval(Pair<Integer, Integer> interval) {
        if (debugPrints.get()) info("set interval to " + interval.getLeft() + " " + interval.getRight());
        workingInterval = interval;
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
            return generateStepCheckerLayers(blockList, blockPaletteDict);
        } catch (Exception e) {
            warning("Failed to load NBT file: " + e.getMessage());
            e.printStackTrace();
            requestDisable();
            return false;
        }
    }

    /**
     * Classic staircase NBT -> step-checker layers, via the reusable converter (a
     * faithful port of the PROVEN tools/manualrun/convert_step.py oracle - see
     * StepCheckerLayers for the encoding rules).
     * @return false when the file is not a CLASSIC staircase export (e.g. a pre-encoded
     *  step-checker/2-layer suppress export) — those stack phases vertically and would
     *  silently produce wrong layers.
     */
    private boolean generateStepCheckerLayers(NbtList blockList, HashMap<Integer, Pair<Block, Integer>> blockPaletteDict) {
        StepCheckerLayers converted = StepCheckerLayers.fromClassicStaircase(blockList, blockPaletteDict, fillerBlock.get());
        if (converted.lastRejectReason != null) {
            error("§c'" + mapFile.getName() + "' is not a CLASSIC staircase export (" + converted.lastRejectReason);
            return false;
        }
        stepLayers = converted;
        int recTotal = converted.recTotalBlocks();
        int domTotal = converted.domTotalBlocks();
        info("Step-checker layers: §a64 pairs§7, rec §a" + recTotal + "§7, dom §a" + domTotal
            + "§7, grand §a" + (recTotal + domTotal) + "§7 blocks");
        return true;
    }

    // --- Phase 1: batch assembly (the placement-plan source for the slave) ---

    /**
     * World-coordinate placement plan for one regular batch k (0..62) - see
     * StepCheckerLayers.assembleBatch for the window layout.
     */
    public HashMap<BlockPos, Block> assembleBatch(int k) {
        return stepLayers == null ? new HashMap<>() : stepLayers.assembleBatch(k, lowerMapCorner);
    }

    /** Priming build: recessive-only floor (doc §4). */
    public HashMap<BlockPos, Block> assemblePriming() {
        return stepLayers == null ? new HashMap<>() : stepLayers.assemblePriming(lowerMapCorner);
    }

    /** Closing build: dominant pair 63 at corner+2 (doc §4). */
    public HashMap<BlockPos, Block> assembleClosing() {
        return stepLayers == null ? new HashMap<>() : stepLayers.assembleClosing(lowerMapCorner);
    }

    /**
     * Phase-1 self-check: per-batch plan sizes (debug) + summary with print-box containment.
     * The printed numbers are compared offline against the python oracle (convert_step.py +
     * the same y-offset assembly); block-level world equality is verified by the placement
     * engine's gate once it lands.
     */
    private void verifyBatchAssembly() {
        if (stepLayers == null || lowerMapCorner == null) return;
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

        // ---- Start All / Resume All button: starts OR resumes the run on BOTH bots ----
        // Fresh runs begin at start-batch; when the progress file exists the run
        // resumes at the in-progress batch with the same pinned map (crash/restart
        // recovery). The master begins its lifecycle and the slave enters standby
        // ready for the master's TCP commands.
        WButton startAllButton = table.add(theme.button("§aStart All / Resume All")).widget();
        startAllButton.action = () -> {
            int resume = master.cachedResumeBatch > 0 ? master.cachedResumeBatch : master.loadStepProgress();
            info("§aSTART ALL pressed" + (resume > 0 ? " - resuming from batch " + resume : "") + ".");
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
            master.resetStepRun();
        };
        table.row();

        WTable slaveTable = new WTable();
        list.add(slaveTable);

        SlaveTableController slaveController = new SlaveTableController(slaveTable, theme);
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
        renderer.render(event);
    }

    /** Walks the map area's coordinate domain (working interval, 129 rows, y 0-1) in
     *  placement order. The step-checker engine has no cached layer content - the
     *  desiredBlock argument is always null; actions read the live world state. */
    private void forEachMapBlock(MapBlockAction action) {
        for (int x = workingInterval.getLeft(); x <= workingInterval.getRight(); x += linesPerRun) {
            for (int z = 0; z < 129; z++) {
                for (int lineBonus = 0; lineBonus < linesPerRun; lineBonus++) {
                    int adjustedX = x + lineBonus;
                    if (!Utils.isInInterval(workingInterval, adjustedX)) break;
                    for (int y = 0; y < 2; y++) {
                        BlockPos pos = lowerMapCorner.add(adjustedX, y, z);
                        BlockState state = mc.world.getBlockState(pos);
                        if (!action.accept(adjustedX, z, y, state, null)) {
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



    /**
     * The master-side step-checker scheduler is deliberately separate from the legacy printer
     * states above.  The legacy states still drive registration, restocking, and the original
     * one-bot engine; this state machine owns only the measured two-bot W->E lifecycle.
     */


    enum SprintMode {
        Off,
        NotPlacing,
        Always,
        /** Transit legs ride Meteor's Speed module (vanilla mode, 7.192 b/s, timer 1);
         *  printing and breaking always use the default walk speed. */
        Dynamic
    }

    private enum ChestRole {
        Material,
        Food,
        Xp
    }
}

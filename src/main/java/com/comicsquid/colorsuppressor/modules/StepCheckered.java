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
    static final int linesPerRun = 2;

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
    StepCheckerLayers stepLayers;                                    // converted per-pair rec/dom layers (StepCheckerLayers.empty() until the NBT loads)
    int currentBatch = -1;                                           // batch being built (slave)
    int breakStage;                                                  // 0 = printing, 2 = break(2), 4 = break-all
    volatile boolean startBuildingRequested;                         // START ALL deferred trigger
    boolean fullSweepActive;                                         // full-area RESET sweep in progress
    // RESET ladder (checkpoint batches): 32 four-lane columns x 5 z-segments; each
    // segment is broken + picked to 100% and CONFIRMED by a bounded scan before the
    // ladder advances. Columns snake (N->S, S->N) so station moves stay short.
    // BREAK CONFIRMATION: fastBreak sends packets but the server's block-update
    // arrives 1-2 ticks later. These track recently-broken positions and retry
    // any that the server didn't process — BEFORE the bot walks past them.
    int resetPendingScope = -2;                                     // persisted RESET scope (-2 unread, -1 none, >=0 resume)                                     // in-memory mirror of the progress file's batch (idle render + resume UI)
    volatile boolean break2Permitted;                                // master calls (TCP "step:break2")
    volatile boolean breakAllPermitted;                              // master calls (TCP "step:breakall")
    HashMap<BlockPos, Block> currentBuildPlan;                       // the slave's active placement plan
    State state;
    ChestRole pendingRole;
    Pair<Integer, Integer> workingInterval;                         // Interval the bot should work in 0-127
    Pair<BlockPos, Vec3d> cartographyTable;
    Pair<BlockPos, Vec3d> finishedMapChest;
    Pair<BlockPos, Vec3d> anvil;
    Pair<BlockPos, Vec3d> foodChest;
    Pair<BlockPos, Vec3d> xpBottleChest;
    ArrayList<Pair<BlockPos, Vec3d>> mapMaterialChests;
    BlockPos tempChestPos;
    BlockPos nextBlockPos;
    HashMap<Integer, Pair<Block, Integer>> blockPaletteDict;        // Maps palette block id to the Minecraft block and amount
    HashMap<Item, ArrayList<Pair<BlockPos, Vec3d>>> materialDict;   // Maps block to the chest pos and the open position
    Set<ItemStack> toolSet;                                         // Set of all registered tool item stacks
    ArrayList<Integer> availableSlots;
    ArrayList<Integer> availableHotBarSlots;
    ArrayList<Pair<Vec3d, Pair<String, BlockPos>>> checkpoints;     // (GoalPos, (checkpointAction, targetBlock))
    ArrayList<File> startedFiles;
    File mapFolder;
    File mapFile;
    BlockPos lowerMapCorner;
    BlockPos upperMapCorner;

    /** RESET ladder z-segments: inclusive row ranges and station centres (relative). */
    /** ITEM SCAN MARGIN: block drops skid up to 3-4 blocks outside the segment /
     *  print box in any horizontal direction (worst case, user-measured 2026-10-20).
     *  Item scans (arrival, verify walk, batch gates) extend this far past the block
     *  bounds so outside drops are chased and collected, not ignored. */
    /** Route actions during which placement may happen (also gates the Dynamic speed mode:
     *  these + Mining = "working", everything else is transit). */
    static final List<String> ALLOW_PLACE_ACTIONS = Arrays.asList("sprint",
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
    final RestockManager restock = new RestockManager(this);
    final BreakPlaceEngine engine = new BreakPlaceEngine(this);
    final SlaveEngine slave = new SlaveEngine(this);

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
        if (restock.lastInteractedChest != null) {
            sb.append(",\"lastChest\":\"").append(restock.lastInteractedChest.toShortString()).append('"');
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
        slave.onStepMasterCommand(command);
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
                    Integer batch = slave.parseStepBatch(parts[1]);
                    if (batch != null) master.stepSlaveReadyBatch = batch;
                }
            }
            case "break2done" -> {
                Integer batch = slave.parseStepBatch(parts.length > 1 ? parts[1] : null);
                if (batch != null) master.stepSlaveBreak2DoneBatch = batch;
            }
            case "done" -> {
                Integer batch = slave.parseStepBatch(parts.length > 1 ? parts[1] : null);
                if (batch != null) master.stepSlaveDoneBatch = batch;
            }
            case "closed" -> master.stepSlaveClosingDone = true;
            default -> {
                if (debugPrints.get()) info("Unknown step progress message: " + message);
            }
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
        restock.restockList = new ArrayList<>();
        toolSet = new HashSet<>();
        restock.checkedChests = new ArrayList<>();
        checkpoints = new ArrayList<>();
        startedFiles = new ArrayList<>();
        restock.restockBacklogSlots = new ArrayList<>();
        restock.depositBacklogSlots = new ArrayList<>();
        restock.fullDepositChests = new HashSet<>();
        fullSweepActive = false;   // a module re-toggle must never inherit a dead reset
        lowerMapCorner = null;
        upperMapCorner = null;
        restock.lastInteractedChest = null;
        nextBlockPos = null;
        cartographyTable = null;
        finishedMapChest = null;
        anvil = null;
        foodChest = null;
        xpBottleChest = null;
        pendingRole = null;
        mapMaterialChests = new ArrayList<>();
        engine.lastSwappedMaterial = null;
        restock.toBeHandledInvPacket = null;
        restock.closeNextInvPacket = false;
        timeoutTicks = 0;
        restock.interactTimeout = 0;
        engine.resetForActivation();
        slave.resetForActivation();
        master.resetForActivation();
        currentBatch = -1;
        currentBuildPlan = null;
        breakStage = 0;
        break2Permitted = false;
        breakAllPermitted = false;
        stepLayers = StepCheckerLayers.empty();   // placeholder until the first NBT load

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
        slave.setTransitSpeed(false);
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
            restock.toBeHandledInvPacket = packet;
            timeoutTicks = preRestockDelay.get();
        }
    }


    /** Restock chest response: pickaxe parking (own-chest rule), then take stacks of
     *  the head restock entry (shuffled chest order; partial stacks make progress). */


    // --- Tick pipeline (lives in SlaveEngine.tick; the order of its stages is
    // load-bearing - see each method's notes) ---

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        slave.tick(event);
    }

    /** START ALL deferred trigger: fired one tick after the HUD button enabled the
     *  module, so onActivate + config load have completed before startBuilding runs. */

    /** Deferred disable: pendingDisable is consumed here so toggle() never re-enters
     *  the activation path recursively. */

    /** RESET RESUME (user rule): a disable/crash mid-RESET must not skip it. Standing
     *  by with a persisted reset scope -> re-arm the scoped ladder. */

    /** Manual/TCP break-permission gates. break(2) arms the phase and lets the tick
     *  continue; break-all hands over to startBreakAll and ends the tick. */

    /** State-transition + checkpoint-consumption debug logs (debug-prints setting). */

    /** Slave announces liveness so the master's registered-slave list survives master
     *  re-toggles; also reconnects when the master link was lost (e.g. master restart). */

    /** Chest/table interaction retry timer. */

    /** Pending on-demand hotbar swap (material found in the inventory, not hotbar). */

    /** Restock backlog: one chest-slot take per delay tick while the chest screen stays open. */

    /** Deposit backlog: one matching stack per delay tick while the chest screen stays
     *  open; when it drains, hop to the next correct chest or continue the queued route.
     *  A chest with no room left for the item is marked full and the deposit reroutes
     *  to another registered chest. */

    /** Serpentine pass feed (RESET ladder): keep a break target armed from the segment
     *  around the bot so the pass breaks everything in interaction range wherever it
     *  walks - no walking past blocks, noobline included. NO item targeting from the
     *  feed - queuing item checkpoints here created an infinite loop (feed detects item
     *  outside segment bounds -> queues checkpoint -> handler scans segment -> CLEAN ->
     *  feed detects the same item again). Items are collected by vanilla proximity on
     *  the serpentine path + segment confirmation + verify walk. */

    /** Break-confirmation processing — THE single processor, and it must run EVERY
     *  tick at this point of the pipeline: the tick flow returns early on many ticks
     *  (every checkpoint arrival handler returns), and a second processor further down
     *  used to double-decrement the same timers and silently remove expired entries on
     *  all those ticks — failed fastBreaks then NEVER reached the vanilla fallback.
     *  Confirmed (now air) → remove. Expired + still solid → engine.needsVanillaBreak. */

    /** The armed break target: clear it once the world shows air, otherwise keep
     *  sending distinct breaks at the per-tick budget. */

    /** Handle a pending block-entity interaction response packet, then any queued
     *  screen close. @return true when a packet was handled (tick stops). */

    /** Checkpoint arrival: anti-stuck guard, the action dispatch, and the empty-queue
     *  fallbacks. @return true when the tick must stop; false when the queue still has
     *  a next checkpoint (caller re-reads the goal). */

    /** break2Done: either queue the second repair pass, verify break(2), or hand back
     *  to the route. @return true when the tick must stop. */

    /** breakAllDone ARRIVAL GATE — same logic as the RESET serpentine: the breaking
     *  pass walked the lane, but the batch is NOT handed off to batchExit until the
     *  window is recycled SERVER-CONFIRMED (client air = server air — fastBreak never
     *  predicts) and its drops are collected. In-flight drops are dwelt for;
     *  stragglers are chased through the hybrid Mining loop.
     *  @return true when the tick must stop; false = advance to batchExit. */

    /** collectRun SINGLE-RUN COLLECTOR: re-target the nearest drop at/ahead of the bot
     *  (z never moves more than 1.5 blocks south) - the run sweeps the whole window
     *  monotonically south -> north and ends clean. No collection happens anywhere
     *  before break(4) completes. Always ends the tick. */

    /** sweepTick COMBINED spatial-awareness SWEEP: nearest actionable target (block or
     *  item), using the shared findNearestGroundItem helper. Always ends the tick. */

    /** resetPass/resetStation ARRIVAL: ALWAYS Mining during reset — Walking disables
     *  the feed (no break targets armed), so any block in the path walls the bot. The
     *  serpentine walk already cleared everything in range; confirm the segment with a
     *  bounded scan; only stragglers (bounced drops, unconfirmed breaks) get targeted
     *  cleanup here - no greedy nearest-first hopping across the segment. Always ends
     *  the tick (the ladder queue advances on its own). */

    /** YAW control while walking: during breaking sweep passes (sweepTick,
     *  fullSweepTick, resetStation, resetPass) the walking target is a BLOCK being
     *  chased - the camera must NOT follow it (that causes random-facing swings).
     *  Instead, keep the yaw locked to the dominant axis of movement (the
     *  serpentine/lane direction). fastBreak doesn't need any facing direction - the
     *  packets go out regardless. */

    /** Sprint policy: transit and work legs sprint unless sprint-mode is Off. */

    /** The next place/break work target for the current state (null = none). */

    /** ROLLING HOTBAR LOOKAHEAD (user rule: "always, for the whole batch, not just at
     *  start"). FIRST-USE-Z window scan + BELADY MULTI-SWAP: walking order is N->S, so
     *  scan the plan's z-WINDOW AHEAD [botZ-2, botZ+24] and record each material's
     *  FIRST-USE z; ALL missing window materials are staged (cascade of up to 4
     *  swaps/sec), each evicting the resident with the FARTHEST next-use z (true
     *  Belady). Multi-stack materials: an emptied hotbar stack counts as missing, so
     *  the next carried stack of the same item stages automatically. */

    /** The tick's work engine once a target passed the range gate: batch multi-place
     *  or batch break (reserved pickaxe + distinct sends). Sets nextBlockPos before
     *  calling (the armed target). */

    // Restocking



    /** Compute what the current plan still needs, drop what is in the way, and queue the
     *  refill trip - the ONE entry point shared by the calculateRefill checkpoint, batch
     *  prep, and the mid-print supply run. */



    // Block Interactions


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

    /** DISTINCT-POSITION MULTI-SEND (user target: 25-30 breaks/s). The old loop
     *  stopped after ONE send because the client cannot see the server's confirmation
     *  for 1-2 ticks - capping the rate at ~10-12/s. The pending-confirmation guard
     *  is PER-POSITION: sending N DIFFERENT positions in the same tick is safe (no
     *  re-send of a pending position ever happens). This sends up to maxSends distinct
     *  in-range targets per tick, skipping pending ones via the exclusion set.
     *  @return number of break actions sent this tick */

    /** Hold the FASTEST TOOL for the target block (axe for wood, pickaxe for stone).
     *  Ranks the registered toolSet against the block, finds a matching stack in the
     *  inventory, and selects it. Falls back to the reserved pickaxe (slot 8). */

    /** HYBRID BREAK SEND - the ONE copy of the break rules. One fastBreak per position
     *  per confirmation window (re-sending START/STOP every tick resets the server's
     *  elapsed-time validation); blocks fastBreak already failed on progress through
     *  vanilla mining instead (removal only via the air-check cleanup). Callers guard
     *  the pending window themselves when a skip vs stop-loop distinction matters.
     *  This logic MUST NOT be copied per call site - the pre-dedup copies drifted and
     *  caused the server-clock-reset starvation bug. */

    /** Is any of this building material currently carried (hotbar or main inventory)? */

    /** Is this item currently held in one of the staging hotbar slots? */

    /** @return 1 = placement sent (hotbar hit), 0 = swap pending, -1 = material missing
     *  (swap/restock already triggered). The multi-place loop uses the code to keep
     *  sending distinct positions while material is in the hotbar and stop cleanly
     *  on a swap. */

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
        if (availableSlots.isEmpty() && !restock.setupSlots()) return false;
        return true;
    }

    void startBuilding() {
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
    void saveResetPending(int scopeCol) {
        StepFiles.saveResetPending(mapFolder, scopeCol);
    }

    private int loadResetPending() {
        return StepFiles.loadResetPending(mapFolder);
    }

    void clearResetPending() {
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

    /**
     * Queues the survival preparation shared by regular batches, priming, and closing.  The
     * queue order is material chests -> pickaxe chest (if needed) -> food -> hand setup; callers
     * then append their print route.  Every trip remains on the north corridor.
     */

    /** Consume one queued master command on the slave's client thread. */


    /** BATCH 0, phase 1 - the floor half (recessive pairs 0+1). Identical route shape,
     *  signals and machinery to every other batch: supply -> print pass -> awaitBreak2
     *  (sends ready:0). The "prime" command is the only difference from "build:0" - it
     *  builds the floor half that W1 records before the tops exist (split-build
     *  physics). The supply run fetches the tops materials too, so the tops pass needs
     *  no refill; mid-print shortages use the emergency restock. */

    /** Build the final dominant pair 63 after regular batch 62 has been recycled. */
    /** Builds the RESET ladder from (fromCol, fromSeg) in snake order - one station per
     *  (column, segment) - and appends the two-vantage final verification. */

    /** Appends the RESET FINISH (user rule, 2026-10-20): NO verify-walk stations -
     *  the ladder's per-segment gates already confirm every segment clean (census with
     *  the bounce margins) before it advances, so a separate verification walk is
     *  redundant checkpoints. After the ladder: deposit the collected blocks, then the
     *  slave PARKS at the north staging point where every run starts. Exit north via
     *  the corridor (never a diagonal beeline across the box). */

    
    

    /** Nearest non-air block inside a bounded column-segment area of the print box. */

    /** Nearest pickup-able ground item inside a bounded column-segment area. */

    /** Ground-collided, pickup-able, unowned drop - the ONE filter every item scanner
     *  shares. (itemCensusInArea deliberately splits these states for its census.) */

    /** RESET verification logging: counts EVERY ItemEntity in a bounded area, split by
     *  the pickup filters findNearestItemInArea applies. ready = would be targeted now;
     *  falling / pickupDelay / owned = invisible to the CLEAN check at this instant.
     *  A CLEAN verdict with falling+pickupDelay > 0 means the segment was declared clean
     *  while drops from the final breaks were still in flight (they get left behind). */

    /** RESET verification logging: break-confirmations still in flight inside the
     *  window (fastBreak sent, server block-update not yet received). */

    /** RESET verification logging: non-air blocks (all print-box layers) inside the window. */

    /** RESET verification logging: blocks flagged for multi-tick vanilla breaking inside the window. */

    /** Master RESET: break every remaining batch block, deposit the items, return to standby. */

    /**
     * Scans the print box (128 lanes x 128 rows x y99-102) for the nearest non-air block
     * from the bot's current position. Only checks loaded chunks (render distance).
     */

    /** Exclusion-set variant for the distinct-position multi-sender. */

    /** Count of plan blocks still standing (not air) in the live world. */

    /** Collection-run target: the nearest ready drop in the batch window AT/AHEAD of
     *  the bot (z never more than 1.5 blocks behind) so the run advances monotonically
     *  south -> north. Falls back to the nearest drop anywhere if everything left is
     *  behind (e.g. an interrupted run). A drop re-picked 10x without disappearing is
     *  blacklisted for this batch (unreachable) and skipped. */

    /** Nearest still-standing plan block to the bot (break-all straggler chase target). */




    /** Route to the batch via the north corridor (one block outside the area): out north,
     *  along the corridor, then the queued batch checkpoints take over. The slave never
     *  crosses the map area except entering/exiting at the north side. */

    // --- Dynamic transit speed (Meteor Speed module, never copied) ---

    /** Dynamic sprint mode: transit legs (corridor, supply trips, waits between passes)
     *  ride Meteor's Speed module - vanilla mode, 7.192 b/s, timer 1 - while printing
     *  and breaking always run at the default walk speed. Toggled from our tick; the
     *  module is force-disabled whenever we stop using it (incl. onDeactivate/abort). */



    /** Direction-agnostic passes: the slave starts a print/break route from whichever
     *  end of the window it is already on (south half -> south-first mirrored route). */
    /** The walking lane (lane 2 center) of the phase the slave is in: batch k, priming,
     *  or the closing build. Placement is gated on standing centered here. */

    /** Before a new phase prints, make sure the slave is ON lane 2: if the previous
     *  phase (sweep/repair leftovers) left it off the centerline, queue a lateral move
     *  onto it first. Placement stays suppressed until centered (both via the
     *  centerLane action not allowing placement and the picker's lane gate), so the
     *  new batch's floor blocks can never be placed into the bot's own path. */

    /** Placement check at the south end of the print pass. Missing blocks are NOT retried
     *  here - the repair path places them from lane -1 while walking north (different
     *  vantage), then one more lane 2 pass, before the break(2) wait. */

    /** Strict world-truth gate used by priming and closing, where a repair route is undefined. */

    /** Strict check after the repair passes: proceed or stop for diagnosis. */

    /** {missing, wrong} counts of the active plan against the live world - the ONE
     *  shared scan behind every placement-verification gate (pre-dedup copies drifted
     *  and could disagree about the same pass). */

    /** break(2) completeness: Y2/Y3 of the window's west 2 lanes must be gone. */

    /** On the master's break-all call: deposit every building block first (north corridor
     *  trip), then break the whole window from lane 2 north -> south, collect everything,
     *  and exit north along lane 2 picking up any leftovers. */

    /** A chest to deposit building blocks into: one already stocking a held material, else
     *  the nearest registered material chest. */

    /** Queue the given chest at the HEAD of the route as the next deposit stop. */


    /** Does the OPEN chest have room for this stack (empty slot or mergeable partial)? */

    /** A DIFFERENT chest for the remaining carried materials, nearest first: prefers a chest
     *  that stocks a carried item; falls back to ANY registered chest not marked full - and
     *  registers that fallback chest as a new stocking location for the item (remembered for
     *  the rest of the run, so future deposits/restocks use it too). */

    /** Mid-run supply trip: exit north from whatever lane the bot is on, refill at the chest
     *  station (materials while printing; a pickaxe when it broke/went missing), then resume
     *  the interrupted pass. All targets are world-truth based, so resuming is exact. */
    /** Emergency-trip RETURN discipline: after a chest visit the resumed route must be
     *  re-entered through the NORTH checkpoint - corridor back to the batch lane, then
     *  south along the lane. Without this, the bot beelined diagonally from the north
     *  chest straight across the printed box to the route's first (mid/south) position
     *  (user callout, live 2026-10-20). */


    /** Rebuilds the remaining route after an interruption (supply run / deposit trip).  The
     *  2k lane formula applies to every batch (batch 0 included - its floor half uses
     *  the same route); closing (63) keeps its own staging lane and names. */

    /** Inventory filled up mid-break: deposit trip through the north corridor, then resume. */

    /** Final check at the north exit: everything recycled, pickaxe back to the offhand.
     *  Same verification logic as the RESET completion gate: the census sees drops the
     *  filtered scans cannot (falling / pickup-delayed), in-flight drops are DWELT for
     *  instead of abandoned, a full inventory deposits before re-verifying, and re-arm
     *  retries are only consumed when the sweep state is FROZEN (no progress). */

    /** Batch-window item census {ready, falling, pickupDelay, owned} — same states the
     *  RESET census uses, over the batch's recycling window incl. its item margin. */

    /** West lane x of the current batch's 4-lane recycling window (world coords). */


            /** Shared: nearest ground-collided pickup-able ItemEntity near the player, within the
     *  print box plus a margin (items bounce outside the exact box when blocks break).
     *  Used by both the batch SWEEP and the full-area RESET — one function, no duplicates. */

    /** Shared: walk to a ground item for vanilla proximity pickup (used by SWEEP and RESET). */

    /** Count ground-collided pickup-able item entities within the current batch window. */

    /** Nearest unplaced plan block within reach that does not intersect the player's body. */

    /** Exclusion-set variant: nearest eligible position NOT in `exclude` (multi-place). */

    /** break target: nearest still-standing plan block in reach (break(2) = west lanes' Y2/Y3 only). */

    /** Exclusion-set variant: returns the nearest eligible target NOT in `exclude` —
     *  lets engine.sendDistinctBreaks pick several DIFFERENT positions per tick. */

    /** Pieces of food currently in the OFFHAND stack (0 if it holds something else). */

    /** Finds the pickaxe with the HIGHEST remaining durability in the inventory (no other
     *  tool qualifies); -1 if none. Best-first, not first-found: the prep check must
     *  measure the best pickaxe and slot 8 must arm the freshest one - first-found armed
     *  stale pickaxes while fresh ones sat deeper in the inventory, re-triggering a fetch
     *  every batch prep (live 2026-10-20: five pickaxes accumulated). */


    /** True when the chest currently being interacted with is registered as stocking a
     *  pickaxe - the only chest a used pickaxe may be parked into. */

    /** True when ANY pickaxe is carried (slot 8 or anywhere in the inventory) - worn
     *  ones count too: exchanges happen only at the pre-batch restock, so mid-run a
     *  worn pickaxe is still the tool in hand (user rule, 2026-10-20). */

    /** Any registered pickaxe: from the tool set or any material-chest registration. */

    /** Walk to the restock station's pickaxe chest, take one pickaxe, then come back to the
     *  staging checkpoint and continue. No failure path - the chest is registered. */

    /** Pickaxe into its reserved hotbar slot 8 (from wherever it is in the inventory). */

    /** A food stack into the offhand (eaten from there); swaps out whatever it holds. */

    /** Hand setup at the batch staging: pickaxe into reserved slot 8, food into the offhand,
     *  main hand free for placing. */

    /** Ensures the reserved pickaxe slot 8 is filled before a breaking pass (the mining
     *  branch selects it directly). */

    // Inventory Management




    /** Total free item capacity across the inventory: empty slots count as a full stack each,
     *  partial stacks count their remaining headroom. Deposit trips only fire at ZERO -
     *  merging collected drops into partial stacks is free space and must not trigger them. */


    /** Working interval bookkeeping (restock.swapIntoHotbar's eviction heuristic reads it). */
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
        slave.pendingDisable = true;
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

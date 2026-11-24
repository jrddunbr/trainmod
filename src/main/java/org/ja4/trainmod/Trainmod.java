package org.ja4.trainmod;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Trainmod.ID)
public final class Trainmod {
    static final String ID = "trainmod";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(ID);
    private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS =
        DeferredRegister.create(Registries.SOUND_EVENT, ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ID);

    static final Map<TrackGeometry.Kind, DeferredBlock<TrackBlock>> TRACK = new EnumMap<>(TrackGeometry.Kind.class);
    static final Map<TrackGeometry.Kind, DeferredItem<TrackItem>> TRACK_ITEMS = new EnumMap<>(TrackGeometry.Kind.class);
    static final Map<TrainVehicle.Kind, DeferredHolder<EntityType<?>, EntityType<TrainVehicle>>> VEHICLES =
        new EnumMap<>(TrainVehicle.Kind.class);
    static final Map<TrainVehicle.Kind, DeferredItem<VehicleItem>> VEHICLE_ITEMS = new EnumMap<>(TrainVehicle.Kind.class);
    static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
        net.minecraft.world.level.block.entity.BlockEntityType<SwitchPoints>> SWITCH_POINTS =
        BLOCK_ENTITIES.register("switch_points", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
            SwitchPoints::new, TRACK.values().stream().map(DeferredBlock::get).filter(block -> block.kind.turnout())
                .toArray(net.minecraft.world.level.block.Block[]::new)));

    static final DeferredBlock<RailwayBlocks.Ballast> BALLAST =
        BLOCKS.registerBlock("ballast", RailwayBlocks.Ballast::new, p -> p.strength(0.7f).sound(SoundType.GRAVEL).noOcclusion());
    static final DeferredBlock<RailExtruder> RAIL_EXTRUDER = BLOCKS.registerBlock("rail_extruder", RailExtruder::new,
        p -> p.strength(3.5f).sound(SoundType.METAL).requiresCorrectToolForDrops());
    static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
        net.minecraft.world.level.block.entity.BlockEntityType<RailExtruder.Machine>> RAIL_EXTRUDER_ENTITY =
        BLOCK_ENTITIES.register("rail_extruder", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
            RailExtruder.Machine::new, RAIL_EXTRUDER.get()));
    static final DeferredHolder<MenuType<?>, MenuType<RailExtruderMenu>> RAIL_EXTRUDER_MENU =
        MENUS.register("rail_extruder", () -> new MenuType<>(RailExtruderMenu::new, FeatureFlags.DEFAULT_FLAGS));
    static final DeferredHolder<MenuType<?>, MenuType<BallastRegulatorMenu>> BALLAST_REGULATOR_MENU =
        MENUS.register("ballast_regulator", () -> new MenuType<>(BallastRegulatorMenu::new, FeatureFlags.DEFAULT_FLAGS));
    static final DeferredItem<Item> RAIL = ITEMS.registerSimpleItem("rail");
    static final DeferredItem<Item> TIE_BUNDLE = ITEMS.registerSimpleItem("tie_bundle");
    static final DeferredBlock<RailwayBlocks.Stand> STAND =
        BLOCKS.registerBlock("switch_stand", RailwayBlocks.Stand::new, p -> p.strength(1.5f).noOcclusion().noCollision());
    static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
        net.minecraft.world.level.block.entity.BlockEntityType<SwitchStand>> SWITCH_STAND =
        BLOCK_ENTITIES.register("switch_stand", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
            SwitchStand::new, STAND.get()));
    static final DeferredBlock<BufferStop> BUFFER_STOP =
        BLOCKS.registerBlock("buffer_stop", BufferStop::new, p -> p.strength(1.5f).noOcclusion());
    static final DeferredHolder<EntityType<?>, EntityType<BufferStop.Mounted>> MOUNTED_BUFFER =
        ENTITIES.registerEntityType("mounted_buffer", BufferStop.Mounted::new, MobCategory.MISC,
            builder -> builder.sized(0.84f, BufferStop.Mounted.HEIGHT).clientTrackingRange(8));
    static final DeferredItem<BufferStop.Item> BUFFER_ITEM = ITEMS.registerItem("buffer_stop",
        BufferStop.Item::new, p -> p.useBlockDescriptionPrefix());
    static final DeferredBlock<RailwayBlocks.Signal> SIGNAL =
        BLOCKS.registerBlock("signal", RailwayBlocks.Signal::new, p -> p.strength(1.5f).noOcclusion().noCollision());

    static final DeferredBlock<SignalCabin> SIGNAL_CABIN =
        BLOCKS.registerBlock("signal_cabin", SignalCabin::new, p -> p.strength(2.5f).noOcclusion());
    static final DeferredBlock<BridgeBlock> WOOD_TRESTLE = bridge("wood_trestle", BridgeBlock.Kind.TRESTLE,
        net.minecraft.world.level.block.SoundType.WOOD);
    static final DeferredBlock<BridgeBlock> STEEL_GIRDER = bridge("steel_girder", BridgeBlock.Kind.GIRDER,
        net.minecraft.world.level.block.SoundType.METAL);
    static final DeferredBlock<WideGirder> WIDE_STEEL_GIRDER = BLOCKS.registerBlock("wide_steel_girder", WideGirder::new,
        p -> p.strength(2.5f).sound(SoundType.METAL).noOcclusion().dynamicShape()
            .pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE));
    static final DeferredBlock<BridgeBlock> STONE_ARCH = bridge("stone_arch", BridgeBlock.Kind.STONE,
        net.minecraft.world.level.block.SoundType.STONE);
    static final DeferredBlock<CatenaryTower> CATENARY_SINGLE = tower("catenary_single", CatenaryTower.Kind.SINGLE);
    static final DeferredBlock<CatenaryTower> CATENARY_DOUBLE = tower("catenary_double", CatenaryTower.Kind.DOUBLE);
    static final DeferredBlock<CatenaryTower> CATENARY_GANTRY = tower("catenary_gantry", CatenaryTower.Kind.GANTRY);
    static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
        net.minecraft.world.level.block.entity.BlockEntityType<CatenaryTower.Anchor>> CATENARY_ANCHOR =
        BLOCK_ENTITIES.register("catenary_anchor", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
            CatenaryTower.Anchor::new, CATENARY_SINGLE.get(), CATENARY_DOUBLE.get(), CATENARY_GANTRY.get()));
    static final DeferredItem<Item> DIESEL_BUCKET = ITEMS.registerSimpleItem("diesel_bucket",
        p -> p.stacksTo(1).craftRemainder(net.minecraft.world.item.Items.BUCKET));

    static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> WHISTLE = sound("steam_whistle");
    static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> HORN = sound("rs3_horn");
    static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> BELL = sound("train_bell");
    static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> CHUFF = sound("steam_chuff");

    private static DeferredBlock<BridgeBlock> bridge(String id, BridgeBlock.Kind kind, net.minecraft.world.level.block.SoundType sound) {
        return BLOCKS.registerBlock(id, p -> new BridgeBlock(kind, p), p -> p.strength(2.5f).sound(sound).noOcclusion().dynamicShape());
    }

    private static DeferredBlock<CatenaryTower> tower(String id, CatenaryTower.Kind kind) {
        return BLOCKS.registerBlock(id, p -> new CatenaryTower(kind, p),
            p -> p.strength(2).sound(net.minecraft.world.level.block.SoundType.METAL).noOcclusion());
    }

    private static DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent> sound(String name) {
        var id = net.minecraft.resources.Identifier.fromNamespaceAndPath(ID, name);
        return SOUNDS.register(name, () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(id));
    }

    static final DeferredItem<TrackBuilder> BUILDER =
        ITEMS.registerItem("track_builder", TrackBuilder::new, p -> p.stacksTo(1));
    static final DeferredItem<Item> COUPLER = ITEMS.registerItem("coupler", properties -> new Item(properties) {
        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context,
                                    net.minecraft.world.item.component.TooltipDisplay display,
                                    java.util.function.Consumer<Component> tooltip,
                                    net.minecraft.world.item.TooltipFlag flag) {
            tooltip.accept(Component.translatable("tooltip.trainmod.coupler").withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }, properties -> properties.stacksTo(1));

    static {
        ITEMS.registerSimpleBlockItem(BALLAST);
        ITEMS.registerSimpleBlockItem(RAIL_EXTRUDER);
        ITEMS.registerItem("wide_steel_girder", p -> new WideGirder.Kit(WIDE_STEEL_GIRDER.get(), p.useBlockDescriptionPrefix()));
        ITEMS.registerSimpleBlockItem(STAND);
        var retiredSignal = ITEMS.registerSimpleBlockItem(SIGNAL);
        var retiredTrestle = ITEMS.registerSimpleBlockItem(WOOD_TRESTLE);
        var retiredStoneArch = ITEMS.registerSimpleBlockItem(STONE_ARCH);
        ITEMS.registerItem("signal_cabin", p -> new net.minecraft.world.item.DoubleHighBlockItem(SIGNAL_CABIN.get(),
            p.useBlockDescriptionPrefix()));
        for (var block : java.util.List.of(STEEL_GIRDER, CATENARY_SINGLE, CATENARY_DOUBLE, CATENARY_GANTRY)) {
            ITEMS.registerSimpleBlockItem(block);
        }
        for (TrackGeometry.Kind kind : TrackGeometry.Kind.values()) {
            TRACK.put(kind, BLOCKS.registerBlock(kind.id() + "_rail",
                p -> kind == TrackGeometry.Kind.SCISSORS_LONG ? new TrackBlock.LongScissors(kind, p)
                    : kind.parallelTurnout() ? new TrackBlock.ParallelTurnout(kind, p)
                    : kind.turnout() ? new TrackBlock.Turnout(kind, p) : kind.crossing() ? new TrackBlock.Crossing(kind, p)
                    : kind.gradedCurve() ? new TrackBlock.RisingCurve(kind, p) : new TrackBlock(kind, p),
                p -> p.strength(0.7f).noCollision().noOcclusion().pushReaction(net.minecraft.world.level.material.PushReaction.POPPED)));
            if (kind.hasKit()) {
                TRACK_ITEMS.put(kind, ITEMS.registerItem(kind.id() + "_rail", p -> new TrackItem(p, kind),
                    p -> p.stacksTo(kind.stackLimit())));
            }
        }
        ITEMS.addAlias(net.minecraft.resources.Identifier.fromNamespaceAndPath(ID, "diagonal_rail"),
            net.minecraft.resources.Identifier.fromNamespaceAndPath(ID, "straight_rail"));
        for (TrainVehicle.Kind kind : TrainVehicle.Kind.values()) {
            VEHICLES.put(kind, ENTITIES.registerEntityType(kind.id(), (type, level) -> new TrainVehicle(type, level, kind),
                MobCategory.MISC, builder -> builder.sized(kind.width, kind.height).clientTrackingRange(10).updateInterval(1)));
            VEHICLE_ITEMS.put(kind, ITEMS.registerItem(kind.id(), p -> new VehicleItem(p, kind), p -> p.stacksTo(1)));
        }
        TABS.register("railway", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.trainmod.railway"))
            .icon(() -> new ItemStack(VEHICLE_ITEMS.get(TrainVehicle.Kind.LOCOMOTIVE).get()))
            .displayItems((parameters, output) -> ITEMS.getEntries().stream().filter(item -> item != BUILDER && item != retiredSignal
                && item != retiredTrestle && item != retiredStoneArch
                && (item != DIESEL_BUCKET || parameters.holders().lookupOrThrow(Registries.ITEM)
                    .get(VehicleFuel.EXTERNAL_DIESEL)
                    .map(net.minecraft.core.HolderSet::size).orElse(0) == 0)).forEach(item -> output.accept(item.get())))
            .build());
    }

    public Trainmod(IEventBus bus, ModContainer mod) {
        mod.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        mod.registerConfig(ModConfig.Type.SYNCED, ServerConfig.SPEC);
        bus.addListener(TrainControls::register);
        bus.addListener(BoreTickets::register);
        bus.addListener(RailExtruder::registerCapabilities);
        SOUNDS.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        var recipes = DeferredRegister.create(Registries.RECIPE_SERIALIZER, ID);
        recipes.register("diesel", () -> DieselRecipe.SERIALIZER);
        recipes.register(bus);
        TABS.register(bus);
    }
}

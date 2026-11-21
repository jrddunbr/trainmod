package org.ja4.trainmod;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);

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
        BLOCKS.registerBlock("ballast", RailwayBlocks.Ballast::new, p -> p.strength(0.7f).noOcclusion());
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
        BLOCKS.registerBlock("signal", RailwayBlocks.Signal::new, p -> p.strength(1.5f).noOcclusion().noCollision().lightLevel(s -> 7));

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
        ITEMS.registerSimpleBlockItem(STAND);
        ITEMS.registerSimpleBlockItem(SIGNAL);
        for (TrackGeometry.Kind kind : TrackGeometry.Kind.values()) {
            TRACK.put(kind, BLOCKS.registerBlock(kind.id() + "_rail",
                p -> kind.turnout() ? new TrackBlock.Turnout(kind, p) : new TrackBlock(kind, p),
                p -> p.strength(0.7f).noCollision().noOcclusion().pushReaction(net.minecraft.world.level.material.PushReaction.POPPED)));
            if (kind != TrackGeometry.Kind.DIAGONAL) {
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
            .displayItems((parameters, output) -> ITEMS.getEntries().stream().filter(item -> item != BUILDER).forEach(item -> output.accept(item.get())))
            .build());
    }

    public Trainmod(IEventBus bus, ModContainer mod) {
        mod.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        bus.addListener(TrainControls::register);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
    }
}

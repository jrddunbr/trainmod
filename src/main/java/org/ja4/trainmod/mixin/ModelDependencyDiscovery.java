package org.ja4.trainmod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ModelManager.class)
abstract class ModelDependencyDiscovery {
    @WrapOperation(
        method = "discoverModelDependencies(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;"
            + "Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;"
            + "Lnet/neoforged/neoforge/client/model/standalone/StandaloneModelLoader$LoadedModels;)"
            + "Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;values()Ljava/util/Collection;", ordinal = 0),
        require = 0
    )
    private static Collection<BlockStateModel.UnbakedRoot> trainmod$uniqueBlockModelRoots(
            Map<BlockState, BlockStateModel.UnbakedRoot> byState,
            Operation<Collection<BlockStateModel.UnbakedRoot>> values) {
        // Every state of a multipart block points at the same root and uses the same dependency resolver.
        var seen = Collections.newSetFromMap(new IdentityHashMap<BlockStateModel.UnbakedRoot, Boolean>());
        var roots = new ArrayList<BlockStateModel.UnbakedRoot>();
        for (var model : values.call(byState)) {
            if (seen.add(model)) roots.add(model);
        }
        return roots;
    }
}

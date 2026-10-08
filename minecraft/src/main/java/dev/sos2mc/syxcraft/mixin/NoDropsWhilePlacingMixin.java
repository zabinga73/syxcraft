package dev.sos2mc.syxcraft.mixin;

import dev.sos2mc.syxcraft.place.WorldWriter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No item drops while a city is being built: rebuilding the ground breaks the plants on it, in caves and under the old
 * hills too (leaf litter, mushrooms, flowers, lily pads), and each one dropped as an item stack, tens of thousands in a
 * forest, mostly in chunks that unload before anything can tidy them up. Only while a placement step runs.
 */
@Mixin(ServerLevel.class)
public class NoDropsWhilePlacingMixin {
	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void syxcraft$noDropsWhilePlacing(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (WorldWriter.placing && entity instanceof ItemEntity)
			cir.setReturnValue(false);
	}
}

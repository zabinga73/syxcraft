package dev.sos2mc.syxcraft.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Citizens placed by Syxcraft keep their farmer job but leave the crops alone: vanilla farmers strip every ripe crop
 * and, with no seeds, never replant, so a freshly placed city's fields went bare within minutes.
 */
@Mixin(HarvestFarmland.class)
public class HarvestFarmlandMixin {
	@Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
	private void syxcraft$citizensDontHarvest(ServerLevel level, Villager body, CallbackInfoReturnable<Boolean> cir) {
		if (body.entityTags().contains("syxcraft_citizen"))
			cir.setReturnValue(false);
	}
}

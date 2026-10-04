package dev.sos2mc.syxcraft.place;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.AABB;

/**
 * Bench cushions: a thin brown carpet shown on the seat half of a stair (a block display, so it can sit half a block
 * up), which is also what a player rides when they sit down. Right-click a cushioned bench with an empty hand to
 * sit; sneak to stand up.
 */
public final class Seats {

	static final String TAG = "syxcraft_cushion";

	private Seats() {
	}

	/** a cushion on the stair at x, y, z whose back is towards dir (0 N, 1 E, 2 S, 3 W) */
	static void cushion(ServerLevel level, int x, int y, int z, int dir) {
		cushion(level, x, y, z, dir, "minecraft:brown_carpet");
	}

	static void cushion(ServerLevel level, int x, int y, int z, int dir, String carpet) {
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		if (d == null)
			return;
		// the seat is the half of the block away from the back; the entity sits in its middle on the seat surface
		double ox = dir == 1 ? 0.25 : dir == 3 ? 0.75 : 0.5, oz = dir == 2 ? 0.25 : dir == 0 ? 0.75 : 0.5;
		boolean alongX = dir == 0 || dir == 2;
		d.snapTo(x + ox, y + 0.5, z + oz, 0, 0);
		d.setBlockState(Palette.parse(carpet));
		// the carpet model fills a block from the entity origin: shrink it to the seat half and centre it
		Vector3f scale = alongX ? new Vector3f(0.96f, 1, 0.48f) : new Vector3f(0.48f, 1, 0.96f);
		d.setTransformation(new Transformation(new Vector3f(-scale.x / 2, 0.001f, -scale.z / 2), new Quaternionf(), scale,
				new Quaternionf()));
		d.addTag(TAG);
		level.addFreshEntity(d);
	}

	static void removeCushions(ServerLevel level, AABB box) {
		for (Display.BlockDisplay d : level.getEntitiesOfClass(Display.BlockDisplay.class, box, e -> e.entityTags().contains(TAG)))
			d.discard();
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty() || player.isShiftKeyDown()
					|| player.isPassenger())
				return InteractionResult.PASS;
			BlockPos pos = hit.getBlockPos();
			if (!(level.getBlockState(pos).getBlock() instanceof StairBlock))
				return InteractionResult.PASS;
			if (!(level instanceof ServerLevel sl)) {
				// the client can't see entity tags; let the server decide
				return InteractionResult.PASS;
			}
			for (Display.BlockDisplay d : sl.getEntitiesOfClass(Display.BlockDisplay.class, new AABB(pos),
					e -> e.entityTags().contains(TAG))) {
				if (d.isVehicle())
					return InteractionResult.PASS; // someone's already sitting there
				return player.startRiding(d, true, true) ? InteractionResult.SUCCESS : InteractionResult.PASS;
			}
			return InteractionResult.PASS;
		});
	}
}

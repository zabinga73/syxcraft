package dev.sos2mc.syxcraft.place;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.sos2mc.syxcraft.map.SyxMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.phys.AABB;

/**
 * Last pass: every Songs of Syx citizen becomes a villager where they stood, named after them, with a profession
 * from their workplace and a look from their race. Re-placing a city replaces its villagers instead of doubling them.
 */
final class Citizens {

	static final String TAG = "syxcraft_citizen";

	private final CityPlan p;
	private final Consumer<String> chat;
	private int spawned, skipped;

	Citizens(CityPlan plan, Consumer<String> chat) {
		this.p = plan;
		this.chat = chat;
	}

	List<Runnable> jobs(WorldWriter w) {
		List<Runnable> jobs = new ArrayList<>();
		if (p.map.people.isEmpty()) {
			jobs.add(() -> chat.accept("Syx: this export has no citizens (made with exporter 0.2 or older)."));
			return jobs;
		}
		jobs.add(() -> removeOld(w));
		List<SyxMap.Person> chosen = sample(p.map.people, p.st.citizenCap);
		for (SyxMap.Person person : chosen)
			jobs.add(() -> spawn(w, person));
		int all = p.map.people.size();
		jobs.add(() -> chat.accept(String.format("Syx: %d citizens moved in%s%s.", spawned,
				chosen.size() < all ? String.format(" (a sample of %,d people, about 1 in %d)", all, Math.round(all / (double) chosen.size())) : "",
				skipped > 0 ? String.format(", %d skipped: outside the area or nowhere to stand", skipped) : "")));
		return jobs;
	}

	/**
	 * Up to cap people, spread evenly: shuffled with a fixed seed, so every workplace and district keeps its share
	 * and placing the same city again picks the same people.
	 */
	static List<SyxMap.Person> sample(List<SyxMap.Person> people, int cap) {
		if (cap <= 0 || people.size() <= cap)
			return people;
		List<SyxMap.Person> res = new ArrayList<>(people);
		java.util.Collections.shuffle(res, new java.util.Random(people.size() * 31L + 7));
		return res.subList(0, cap);
	}

	private void removeOld(WorldWriter w) {
		AABB box = new AABB(p.X0, p.B - CityPlan.BELOW, p.Z0, p.X0 + p.bw, p.B + CityPlan.ABOVE, p.Z0 + p.bh);
		for (Villager v : w.level.getEntitiesOfClass(Villager.class, box, v -> v.entityTags().contains(TAG)))
			v.discard();
	}

	private void spawn(WorldWriter w, SyxMap.Person person) {
		String type = person.type() == null ? "" : person.type();
		// invaders and rioters aren't residents
		if (type.equals("ENEMY") || type.equals("RIOTER") || type.equals("DERANGED")) {
			skipped++;
			return;
		}
		BlockPos at = standingSpot(w, person.x(), person.y());
		if (at == null && person.homeX() >= 0)
			at = standingSpot(w, person.homeX(), person.homeY());
		if (at == null) {
			skipped++;
			return;
		}
		Villager v = EntityTypes.VILLAGER.create(w.level, EntitySpawnReason.COMMAND);
		if (v == null)
			return;
		var reg = w.level.registryAccess();
		v.setVillagerData(v.getVillagerData().withType(reg, villagerType(person.race()))
				.withProfession(reg, profession(person)));
		// any xp keeps a profession without a job-site block (vanilla resets a level-1, 0 xp villager)
		v.setVillagerXp(1);
		if (type.startsWith("CHILD"))
			v.setAge(-24000);
		if (person.name() != null && !person.name().isBlank())
			v.setCustomName(Component.literal(person.name().trim()));
		long h = CityPlan.hash(at.getX() * 3 + 1, at.getZ() * 5 + 2);
		v.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, h % 360, 0);
		v.setPersistenceRequired();
		v.addTag(TAG);
		w.level.addFreshEntity(v);
		spawned++;
	}

	/** a free block in the tile (or right around it) to stand on: two blocks of headroom above a solid floor */
	private BlockPos standingSpot(WorldWriter w, int tx, int ty) {
		for (int r = 0; r <= 1; r++)
			for (int dy = -r; dy <= r; dy++)
				for (int dx = -r; dx <= r; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dy)) != r || !p.inRegion(tx + dx, ty + dy))
						continue;
					int x0 = p.blockX(tx + dx), z0 = p.blockZ(ty + dy);
					for (int v = 0; v < p.s; v++)
						for (int u = 0; u < p.s; u++) {
							int x = x0 + (u + p.s / 2) % p.s, z = z0 + (v + p.s / 2) % p.s;
							for (int y = p.B + 1; y <= p.B + 2; y++)
								if (free(w, x, y, z) && free(w, x, y + 1, z) && !free(w, x, y - 1, z))
									return new BlockPos(x, y, z);
						}
				}
		return null;
	}

	private static boolean free(WorldWriter w, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		var state = w.level.getBlockState(pos);
		return state.getCollisionShape(w.level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	/** villager biome look from the Songs of Syx race */
	static ResourceKey<VillagerType> villagerType(String race) {
		if (race == null)
			return VillagerType.PLAINS;
		return switch (race.toUpperCase()) {
		case "CRETONIAN" -> VillagerType.SWAMP;
		case "DONDORIAN" -> VillagerType.TAIGA;
		case "TILAPI" -> VillagerType.JUNGLE;
		case "GARTHIMI" -> VillagerType.DESERT;
		case "AMEVIA" -> VillagerType.SAVANNA;
		case "ARGONOSH" -> VillagerType.SNOW;
		default -> VillagerType.PLAINS;
		};
	}

	/** villager profession from the workplace's room type, then from the kind of person */
	static ResourceKey<VillagerProfession> profession(SyxMap.Person person) {
		String job = person.job() == null ? "" : person.job();
		String type = person.type() == null ? "" : person.type();
		if (type.startsWith("CHILD"))
			return VillagerProfession.NONE;
		if (!job.isEmpty()) {
			if (job.startsWith("FARM_") || job.startsWith("ORCHARD_") || job.startsWith("PLANTATION_"))
				return VillagerProfession.FARMER;
			if (job.startsWith("FISHERY_"))
				return VillagerProfession.FISHERMAN;
			if (job.startsWith("PASTURE_") || job.startsWith("REFINER_WEAVER") || job.equals("WORKSHOP_TAILOR"))
				return VillagerProfession.SHEPHERD;
			if (job.startsWith("HUNTER") || job.startsWith("REFINER_TANNERY") || job.equals("WORKSHOP_LEATHER"))
				return VillagerProfession.LEATHERWORKER;
			if (job.startsWith("SHRINE_") || job.startsWith("TEMPLE_") || job.startsWith("HOSPITAL") || job.startsWith("PHYSICIAN")
					|| job.startsWith("_DUMP_CORPSE") || job.startsWith("GRAVEYARD") || job.startsWith("TOMB"))
				return VillagerProfession.CLERIC;
			if (job.startsWith("SCHOOL") || job.startsWith("UNIVERSITY") || job.startsWith("LIBRARY") || job.startsWith("ADMIN")
					|| job.startsWith("_THRONE") || job.startsWith("SPEAKER_") || job.startsWith("COURT"))
				return VillagerProfession.LIBRARIAN;
			if (job.startsWith("MINE_") || job.startsWith("QUARRY") || job.equals("WORKSHOP_MASON") || job.startsWith("REFINER_BRICK"))
				return VillagerProfession.MASON;
			if (job.startsWith("REFINER_SMELTER") || job.startsWith("WORKSHOP_SMITHY") || job.startsWith("REFINER_COALER")
					|| job.equals("WORKSHOP_MECHANIC") || job.equals("WORKSHOP_TOOL"))
				return VillagerProfession.TOOLSMITH;
			if (job.startsWith("WORKSHOP_WEAPON") || job.startsWith("BARRACKS") || job.startsWith("ARCHERY") || job.startsWith("GUARD"))
				return VillagerProfession.WEAPONSMITH;
			if (job.startsWith("WORKSHOP_ARMOUR") || job.startsWith("WORKSHOP_ARMOR"))
				return VillagerProfession.ARMORER;
			if (job.equals("WORKSHOP_CARPENTER") || job.equals("WORKSHOP_BOWYER") || job.startsWith("WOODCUTTER")
					|| job.startsWith("REFINER_SAWMILL"))
				return VillagerProfession.FLETCHER;
			if (job.equals("WORKSHOP_PAPER") || job.startsWith("TRADE") || job.startsWith("PORT") || job.startsWith("EXPORT")
					|| job.startsWith("IMPORT"))
				return VillagerProfession.CARTOGRAPHER;
			if (job.startsWith("EATERY_") || job.startsWith("CANTEEN") || job.startsWith("MARKET_") || job.startsWith("TAVERN")
					|| job.startsWith("REFINER_BAKERY") || job.startsWith("REFINER_BREWERY") || job.startsWith("WORKSHOP_RATION")
					|| job.startsWith("SLAUGHTER") || job.startsWith("_HEARTH"))
				return VillagerProfession.BUTCHER;
			if (job.startsWith("_JANITOR") || job.startsWith("_STOCKPILE") || job.startsWith("_HAULER") || job.startsWith("_CONSTRUCTION")
					|| job.startsWith("LAVATORY_") || job.startsWith("WELL_"))
				return VillagerProfession.NITWIT;
			return VillagerProfession.TOOLSMITH;
		}
		return switch (type) {
		case "SLAVE", "PRISONER" -> VillagerProfession.NITWIT;
		case "NOBILITY" -> VillagerProfession.LIBRARIAN;
		case "SOLDIER", "RECRUIT", "GUARD" -> VillagerProfession.WEAPONSMITH;
		case "STUDENT" -> VillagerProfession.LIBRARIAN;
		default -> VillagerProfession.NONE;
		};
	}
}

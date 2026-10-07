package dev.sos2mc.syxcraft;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.sos2mc.syxcraft.map.SyxMap;
import dev.sos2mc.syxcraft.map.SyxMapFiles;
import dev.sos2mc.syxcraft.net.SyxNet;
import dev.sos2mc.syxcraft.place.Palette;
import dev.sos2mc.syxcraft.place.PlaceSettings;
import dev.sos2mc.syxcraft.place.PlacementJob;
import dev.sos2mc.syxcraft.place.RiverFinder;
import dev.sos2mc.syxcraft.place.Rivers;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Consumer;
import net.minecraft.server.permissions.Permissions;

/** Server side: one placement job at a time, driven from the server tick. */
public final class SyxServer {

	private SyxServer() {
	}

	/** milliseconds of each 50 ms server tick the job may use */
	static final long BUDGET_MS = 30;

	private static PlacementJob job;
	private static UUID owner;
	private static int ticks;
	private static SyxMap cached;
	/** River lineup: the search running before a placement, and what to do with its result */
	private static CompletableFuture<RiverFinder.Result> riverSearch;
	private static Consumer<RiverFinder.Result> riverStart;
	/** the most recent job, kept for /syx render */
	static PlacementJob last;

	static void init() {
		SyxNet.register();
		ServerTickEvents.END_SERVER_TICK.register(SyxServer::tick);

		ServerPlayNetworking.registerGlobalReceiver(SyxNet.RequestFiles.TYPE, (p, ctx) -> {
			ServerPlayer pl = ctx.player();
			ctx.responseSender().sendPacket(new SyxNet.FileList(SyxMapFiles.entries(), pl.getBlockX(), pl.getBlockZ(),
					pl.level().getSeaLevel(),
					FabricLoader.getInstance().getConfigDir().resolve("syxcraft").resolve("palette.json").toString()));
		});
		ServerPlayNetworking.registerGlobalReceiver(SyxNet.RequestInfo.TYPE, (p, ctx) -> {
			SyxMap m = load(p.file());
			if (m == null)
				return;
			int[] b = m.cityBounds();
			ctx.responseSender().sendPacket(new SyxNet.MapInfo(p.file(), m.width, m.height, b[0], b[1], b[2], b[3],
					m.rooms.size(), m.furniture.size(), dev.sos2mc.syxcraft.place.Rivers.pack(m.waterMask(), m.width * m.height)));
		});
		ServerPlayNetworking.registerGlobalReceiver(SyxNet.Start.TYPE, (p, ctx) -> start(ctx.player(), p.settings()));
		ServerPlayNetworking.registerGlobalReceiver(SyxNet.Cancel.TYPE, (p, ctx) -> {
			if (allowed(ctx.player()))
				cancel();
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> commands(dispatcher));
	}

	static boolean allowed(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		return server.isSingleplayerOwner(p.nameAndId()) || p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	static SyxMap load(String file) {
		if (cached != null && cached.path.getFileName().toString().equals(file))
			return cached;
		Path path = SyxMapFiles.find(file);
		if (path == null)
			return null;
		try {
			cached = SyxMap.load(path);
			return cached;
		} catch (Exception e) {
			Syxcraft.LOG.error("could not load {}", path, e);
			return null;
		}
	}

	public static boolean start(ServerPlayer player, PlaceSettings st) {
		if (!allowed(player)) {
			player.sendSystemMessage(Component.literal("Syx: you need operator rights to place a city."));
			return false;
		}
		int cx = st.origin == PlaceSettings.Origin.COORDS ? st.x : player.getBlockX();
		int cz = st.origin == PlaceSettings.Origin.COORDS ? st.z : player.getBlockZ();
		MinecraftServer server = player.level().getServer();
		UUID id = player.getUUID();
		return start(player.level(), st, cx, cz, id, msg -> {
			ServerPlayer pl = server.getPlayerList().getPlayer(id);
			if (pl != null)
				pl.sendSystemMessage(Component.literal(msg));
		});
	}

	public static boolean start(ServerLevel level, PlaceSettings st, int cx, int cz, UUID who, Consumer<String> chat) {
		if ((job != null && job.running()) || riverSearch != null) {
			chat.accept("Syx: a city is already being placed (use Cancel or /syx cancel).");
			return false;
		}
		SyxMap map = load(st.file);
		if (map == null) {
			chat.accept("Syx: can't find or read " + st.file);
			return false;
		}
		if (st.river) {
			// line the city's river up with a Minecraft river first (off the server thread), then place it there
			if (st.scale != 1) {
				chat.accept("Syx: River lineup only works at scale 1.");
				return false;
			}
			int[] region = Rivers.region(map.width, map.height, map.cityBounds(), st.area, st.margin);
			List<Rivers.Exit> exits = Rivers.find(map.waterMask(), map.width, region);
			if (exits.isEmpty()) {
				chat.accept("Syx: no river leaves this area of the map, so there's nothing to line up. Try Whole map or a bigger margin.");
				return false;
			}
			st.height = PlaceSettings.Height.SEA_LEVEL;
			owner = who;
			chat.accept(String.format("Syx: looking for a Minecraft river within %d blocks to line up with (%d river exits)...",
					RiverFinder.RADIUS, exits.size()));
			riverSearch = CompletableFuture.supplyAsync(() -> RiverFinder.find(level, map.waterMask(), map.width, region, exits, cx, cz));
			riverStart = r -> {
				if (r.exitsMet() < 0.25) {
					chat.accept(String.format("Syx: no Minecraft river within %d blocks lines up (best: %d%% of the river's exits). Nothing placed; try somewhere else.",
							RiverFinder.RADIUS, Math.round(r.exitsMet() * 100)));
					return;
				}
				chat.accept(String.format("Syx: lined up with a river at x=%d z=%d (%d blocks away, %d%% of the river's exits meet it).",
						r.x(), r.z(), Math.round(Math.hypot(r.x() - cx, r.z() - cz)), Math.round(r.exitsMet() * 100)));
				job = new PlacementJob(level, map, st, Palette.load(), r.x(), r.z(), msg -> {
					chat.accept(msg);
					Syxcraft.LOG.info(msg);
				});
				job.rivers = exits;
			};
			return true;
		}
		job = new PlacementJob(level, map, st, Palette.load(), cx, cz, msg -> {
			chat.accept(msg);
			Syxcraft.LOG.info(msg);
		});
		owner = who;
		return true;
	}

	public static void cancel() {
		if (riverSearch != null) {
			riverSearch.cancel(true);
			riverSearch = null;
		}
		if (job != null)
			job.cancel();
	}

	private static void tick(MinecraftServer server) {
		if (riverSearch != null) {
			ServerPlayer pl = server.getPlayerList().getPlayer(owner);
			if (riverSearch.isDone()) {
				var search = riverSearch;
				riverSearch = null;
				try {
					riverStart.accept(search.join());
				} catch (Exception e) {
					Syxcraft.LOG.error("river search failed", e);
					if (pl != null)
						pl.sendSystemMessage(Component.literal("Syx: river search failed: " + e));
				}
				if (job == null && pl != null && ServerPlayNetworking.canSend(pl, SyxNet.Progress.TYPE))
					ServerPlayNetworking.send(pl, new SyxNet.Progress("No river lined up", 0, false));
			} else if (++ticks % 10 == 0 && pl != null) {
				if (ServerPlayNetworking.canSend(pl, SyxNet.Progress.TYPE))
					ServerPlayNetworking.send(pl, new SyxNet.Progress("Looking for a river", 0, true));
				pl.sendOverlayMessage(Component.literal("Syx: looking for a river..."));
			}
		}
		if (job == null)
			return;
		last = job;
		boolean was = job.running();
		if (was)
			job.tick(BUDGET_MS);
		if (++ticks % 10 == 0 || (was && !job.running())) {
			ServerPlayer pl = server.getPlayerList().getPlayer(owner);
			if (pl != null) {
				String status = job.status() + " " + Math.round(job.progress() * 100) + "%";
				if (ServerPlayNetworking.canSend(pl, SyxNet.Progress.TYPE))
					ServerPlayNetworking.send(pl, new SyxNet.Progress(job.status(), job.progress(), job.running()));
				if (job.running())
					pl.sendOverlayMessage(Component.literal("Syx: " + status));
			}
		}
		if (!job.running() && ticks % 10 == 0)
			job = null;
	}

	/* ---------------------------------------------------------------- commands */

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("syx")
				.then(Commands.literal("list").executes(SyxServer::list))
				.then(Commands.literal("rivers").then(Commands.argument("file", StringArgumentType.string()).executes(c -> {
					// which areas of a map have a river to line up with (what the placer screen's check mark shows)
					SyxMap m = load(StringArgumentType.getString(c, "file"));
					if (m == null)
						return 0;
					for (int margin : new int[] { -1, 8, 16, 32, 64, 128 }) {
						int[] r = Rivers.region(m.width, m.height, m.cityBounds(), margin < 0 ? PlaceSettings.Area.WHOLE_MAP : PlaceSettings.Area.CITY,
								Math.max(0, margin));
						List<Rivers.Exit> ex = Rivers.find(m.waterMask(), m.width, r);
						c.getSource().sendSystemMessage(Component.literal((margin < 0 ? "Whole map" : "City +" + margin) + ": "
								+ (ex.isEmpty() ? "no river" : ex.size() + " exits " + ex)));
					}
					return 1;
				})))
				.then(Commands.literal("cancel").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
					cancel();
					return 1;
				}))
				.then(Commands.literal("place").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("file", StringArgumentType.string())
								.suggests((c, b) -> {
									for (SyxMapFiles.Entry e : SyxMapFiles.entries())
										b.suggest(StringArgumentType.escapeIfRequired(e.name()));
									return b.buildFuture();
								})
								.executes(c -> place(c, 2, null))
								.then(Commands.argument("scale", IntegerArgumentType.integer(1, 4))
										.executes(c -> place(c, IntegerArgumentType.getInteger(c, "scale"), null))
										.then(Commands.literal("at")
												.then(Commands.argument("x", IntegerArgumentType.integer())
														.then(Commands.argument("z", IntegerArgumentType.integer())
																.executes(c -> place(c, IntegerArgumentType.getInteger(c, "scale"),
																		new int[] { IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z") }))
																// optional topography: flat | random | local, then the hill height
																.then(Commands.argument("topography", StringArgumentType.word())
																		.suggests((c, b) -> {
																			for (PlaceSettings.Topography t : PlaceSettings.Topography.values())
																				b.suggest(t.name().toLowerCase());
																			return b.buildFuture();
																		})
																		.executes(c -> place(c, IntegerArgumentType.getInteger(c, "scale"),
																				new int[] { IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z") }))
																		.then(Commands.argument("hills", IntegerArgumentType.integer(1, 64))
																				.executes(c -> place(c, IntegerArgumentType.getInteger(c, "scale"),
																						new int[] { IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z") }))
																				.then(Commands.argument("valleys", IntegerArgumentType.integer(0, 64))
																						.executes(c -> place(c, IntegerArgumentType.getInteger(c, "scale"),
																								new int[] { IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z") })))))))))))
				.then(Commands.literal("probe").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
									int x = IntegerArgumentType.getInteger(c, "x"), y = IntegerArgumentType.getInteger(c, "y"),
											z = IntegerArgumentType.getInteger(c, "z");
									StringBuilder sb = new StringBuilder("Syx probe");
									for (int dy = 2; dy >= -1; dy--)
										sb.append(" | y=").append(y + dy).append(' ')
												.append(c.getSource().getLevel().getBlockState(new net.minecraft.core.BlockPos(x, y + dy, z)));
									c.getSource().sendSystemMessage(Component.literal(sb.toString()));
									return 1;
								})))))
				.then(Commands.literal("check").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
					if (last == null)
						return 0;
					c.getSource().sendSystemMessage(Component.literal("Syx check: " + Render.checkDoors(last)));
					return 1;
				}))
				// dev: every non-air block over a range of tiles (plus a 3-block margin) to a text file, "x y z state"
				.then(Commands.literal("dump").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("tx", IntegerArgumentType.integer()).then(Commands.argument("ty", IntegerArgumentType.integer())
								.then(Commands.argument("tw", IntegerArgumentType.integer(1, 64)).then(Commands.argument("th", IntegerArgumentType.integer(1, 64))
										.executes(c -> {
											if (last == null)
												return 0;
											var pl = last.plan;
											int tx = IntegerArgumentType.getInteger(c, "tx"), ty = IntegerArgumentType.getInteger(c, "ty");
											int x1 = pl.blockX(tx) - 3, z1 = pl.blockZ(ty) - 3;
											int x2 = pl.blockX(tx + IntegerArgumentType.getInteger(c, "tw")) + 2;
											int z2 = pl.blockZ(ty + IntegerArgumentType.getInteger(c, "th")) + 2;
											StringBuilder sb = new StringBuilder();
											var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
											for (int y = pl.B - 1; y <= pl.B + 24; y++)
												for (int z = z1; z <= z2; z++)
													for (int x = x1; x <= x2; x++) {
														var st = c.getSource().getLevel().getBlockState(pos.set(x, y, z));
														if (!st.isAir())
															sb.append(x - x1).append(' ').append(y - pl.B).append(' ').append(z - z1).append(' ')
																	.append(st).append('\n');
													}
											try {
												Path out = FabricLoader.getInstance().getGameDir().resolve("syxcraft-dump.txt");
												java.nio.file.Files.writeString(out, sb);
												c.getSource().sendSystemMessage(Component.literal("Syx: wrote " + out));
											} catch (java.io.IOException e) {
												c.getSource().sendSystemMessage(Component.literal("Syx: dump failed: " + e));
											}
											return 1;
										}))))))
				.then(Commands.literal("render").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(SyxServer::render)
						.then(Commands.literal("plan").then(Commands.argument("dy", IntegerArgumentType.integer(-64, 64)).executes(c -> {
							try {
								int dy = IntegerArgumentType.getInteger(c, "dy");
								Path out = FabricLoader.getInstance().getGameDir().resolve("syxcraft-plan-" + dy + ".png");
								Render.plan(last, dy, out);
								c.getSource().sendSystemMessage(Component.literal("Syx: wrote " + out));
								return 1;
							} catch (Exception e) {
								c.getSource().sendSystemMessage(Component.literal("Syx: plan failed: " + e));
								return 0;
							}
						})))
						.then(Commands.literal("slice").then(Commands.argument("z", IntegerArgumentType.integer())
								.then(Commands.argument("x1", IntegerArgumentType.integer()).then(Commands.argument("x2", IntegerArgumentType.integer())
										.executes(c -> slice(c, IntegerArgumentType.getInteger(c, "z"), IntegerArgumentType.getInteger(c, "x1"),
												IntegerArgumentType.getInteger(c, "x2")))))))));
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		var entries = SyxMapFiles.entries();
		if (entries.isEmpty())
			c.getSource().sendSystemMessage(Component.literal("No .syxmap files found. Looked in: " + SyxMapFiles.searchDirs()));
		for (SyxMapFiles.Entry e : entries)
			c.getSource().sendSystemMessage(Component.literal(e.name() + "  (" + e.city() + ", " + e.exported() + ")"));
		return entries.size();
	}

	private static int place(CommandContext<CommandSourceStack> c, int scale, int[] at) {
		PlaceSettings st = new PlaceSettings();
		st.file = StringArgumentType.getString(c, "file");
		st.scale = scale;
		try {
			// "local", "local+sea", "flat+domed+sea": topography, then optionally a ground height mode (auto | sea |
			// custom), a roof style (hipped | pointed | domed | flat | mixed), "citizens", "peaks"/"nopeaks" and "river", in any order
			String[] t = StringArgumentType.getString(c, "topography").toUpperCase().split("\\+");
			for (int k = 1; k < t.length; k++) {
				if (t[k].startsWith("SEA"))
					st.height = PlaceSettings.Height.SEA_LEVEL;
				else if (t[k].equals("CITIZENS"))
					st.set(PlaceSettings.CITIZENS, true);
				else if (t[k].equals("PEAKS"))
					st.peaks = true;
				else if (t[k].equals("NOPEAKS"))
					st.peaks = false;
				else if (t[k].equals("RIVER"))
					st.river = true;
				else if (t[k].equals("AUTO") || t[k].equals("CUSTOM"))
					st.height = PlaceSettings.Height.valueOf(t[k]);
				else
					st.roof = PlaceSettings.Roof.valueOf(t[k]);
			}
			st.topography = PlaceSettings.Topography.valueOf(t[0]);
		} catch (IllegalArgumentException e) {
			// not given (or not a known one): flat
		}
		try {
			st.hills = IntegerArgumentType.getInteger(c, "hills");
		} catch (IllegalArgumentException e) {
		}
		try {
			st.valleys = IntegerArgumentType.getInteger(c, "valleys");
		} catch (IllegalArgumentException e) {
		}
		ServerPlayer p = c.getSource().getPlayer();
		if (p != null && at == null)
			return start(p, st) ? 1 : 0;
		int x = at != null ? at[0] : 0, z = at != null ? at[1] : 0;
		CommandSourceStack src = c.getSource();
		return start(src.getLevel(), st, x, z, p != null ? p.getUUID() : null,
				msg -> src.sendSystemMessage(Component.literal(msg))) ? 1 : 0;
	}

	private static int slice(CommandContext<CommandSourceStack> c, int z, int x1, int x2) {
		if (last == null)
			return 0;
		try {
			Path out = FabricLoader.getInstance().getGameDir().resolve("syxcraft-slice-" + z + ".png");
			Render.slice(last, z, Math.min(x1, x2), Math.max(x1, x2), out);
			c.getSource().sendSystemMessage(Component.literal("Syx: wrote " + out));
			return 1;
		} catch (Exception e) {
			c.getSource().sendSystemMessage(Component.literal("Syx: slice failed: " + e));
			return 0;
		}
	}

	/** top-down picture of the last placement (map colours with height shading), for checking results */
	private static int render(CommandContext<CommandSourceStack> c) {
		PlacementJob j = last;
		if (j == null) {
			c.getSource().sendSystemMessage(Component.literal("Syx: nothing placed yet."));
			return 0;
		}
		try {
			Path out = FabricLoader.getInstance().getGameDir().resolve("syxcraft-render.png");
			Render.topDown(j, out);
			c.getSource().sendSystemMessage(Component.literal("Syx: wrote " + out));
			return 1;
		} catch (Exception e) {
			c.getSource().sendSystemMessage(Component.literal("Syx: render failed: " + e));
			return 0;
		}
	}
}

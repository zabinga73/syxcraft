package dev.sos2mc.syxcraft.net;

import java.util.ArrayList;
import java.util.List;

import dev.sos2mc.syxcraft.Syxcraft;
import dev.sos2mc.syxcraft.map.SyxMapFiles;
import dev.sos2mc.syxcraft.place.PlaceSettings;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Payloads between the placement screen (client) and the placement job (server). */
public final class SyxNet {

	private SyxNet() {
	}

	static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Syxcraft.MOD_ID, path));
	}

	/** client asks for the list of .syxmap files the server can see */
	public record RequestFiles() implements CustomPacketPayload {
		public static final Type<RequestFiles> TYPE = id("request_files");
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestFiles> CODEC = StreamCodec.unit(new RequestFiles());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record FileList(List<SyxMapFiles.Entry> entries, int playerX, int playerZ, int seaLevel, String palettePath)
			implements CustomPacketPayload {
		public static final Type<FileList> TYPE = id("file_list");
		public static final StreamCodec<RegistryFriendlyByteBuf, FileList> CODEC = CustomPacketPayload.codec(FileList::write, FileList::read);

		void write(RegistryFriendlyByteBuf b) {
			b.writeVarInt(entries.size());
			for (SyxMapFiles.Entry e : entries) {
				b.writeUtf(e.name());
				b.writeUtf(nn(e.city()));
				b.writeUtf(nn(e.save()));
				b.writeUtf(nn(e.exported()));
				b.writeVarInt(e.width());
				b.writeVarInt(e.height());
				b.writeLong(e.size());
				b.writeLong(e.modified());
			}
			b.writeInt(playerX);
			b.writeInt(playerZ);
			b.writeInt(seaLevel);
			b.writeUtf(palettePath);
		}

		static FileList read(RegistryFriendlyByteBuf b) {
			int n = b.readVarInt();
			List<SyxMapFiles.Entry> l = new ArrayList<>();
			for (int i = 0; i < n; i++)
				l.add(new SyxMapFiles.Entry(b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readVarInt(),
						b.readLong(), b.readLong()));
			return new FileList(l, b.readInt(), b.readInt(), b.readInt(), b.readUtf());
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** client asks for the footprint of one file (for the preview outline) */
	public record RequestInfo(String file) implements CustomPacketPayload {
		public static final Type<RequestInfo> TYPE = id("request_info");
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestInfo> CODEC = CustomPacketPayload.codec(
				(RequestInfo r, RegistryFriendlyByteBuf b) -> b.writeUtf(r.file), b -> new RequestInfo(b.readUtf()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** map size, built-up area and a few counts */
	public record MapInfo(String file, int width, int height, int cx0, int cy0, int cx1, int cy1, int rooms, int furniture)
			implements CustomPacketPayload {
		public static final Type<MapInfo> TYPE = id("map_info");
		public static final StreamCodec<RegistryFriendlyByteBuf, MapInfo> CODEC = CustomPacketPayload.codec(MapInfo::write, MapInfo::read);

		void write(RegistryFriendlyByteBuf b) {
			b.writeUtf(file);
			for (int v : new int[] { width, height, cx0, cy0, cx1, cy1, rooms, furniture })
				b.writeVarInt(v);
		}

		static MapInfo read(RegistryFriendlyByteBuf b) {
			return new MapInfo(b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
					b.readVarInt(), b.readVarInt(), b.readVarInt());
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Start(PlaceSettings settings) implements CustomPacketPayload {
		public static final Type<Start> TYPE = id("start");
		public static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = CustomPacketPayload.codec(
				(Start s, RegistryFriendlyByteBuf b) -> s.settings.write(b), b -> new Start(PlaceSettings.read(b)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Cancel() implements CustomPacketPayload {
		public static final Type<Cancel> TYPE = id("cancel");
		public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> CODEC = StreamCodec.unit(new Cancel());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Progress(String status, float progress, boolean running) implements CustomPacketPayload {
		public static final Type<Progress> TYPE = id("progress");
		public static final StreamCodec<RegistryFriendlyByteBuf, Progress> CODEC = CustomPacketPayload.codec(
				(Progress p, RegistryFriendlyByteBuf b) -> {
					b.writeUtf(p.status);
					b.writeFloat(p.progress);
					b.writeBoolean(p.running);
				}, b -> new Progress(b.readUtf(), b.readFloat(), b.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static String nn(String s) {
		return s == null ? "" : s;
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(RequestFiles.TYPE, RequestFiles.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RequestInfo.TYPE, RequestInfo.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Start.TYPE, Start.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Cancel.TYPE, Cancel.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(FileList.TYPE, FileList.CODEC, 1 << 20);
		PayloadTypeRegistry.clientboundPlay().register(MapInfo.TYPE, MapInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Progress.TYPE, Progress.CODEC);
	}

	@SuppressWarnings("unused")
	private static void unused(FriendlyByteBuf b) {
	}
}

package dev.mcgps;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory map tiles (nothing is written to disk). Render thread only, except the download step. */
public final class TileCache {
	private static final int MAX_TILES = 320;
	private static final int MAX_IN_FLIGHT = 6;
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "mcgps-tiles");
		t.setDaemon(true);
		return t;
	});
	public static final String USER_AGENT = "MCGPS/1.0 (Minecraft Fabric mod)";

	private enum Status { LOADING, READY, FAILED }

	private static final class Entry {
		Status status = Status.LOADING;
		Identifier id;
		long failedAt;
	}

	private final Map<String, Entry> tiles = new LinkedHashMap<>(64, 0.75f, true);
	private final AtomicInteger inFlight = new AtomicInteger();
	private int seq;

	public static String url(String style, int z, int x, int y) {
		return "street".equals(style)
				? "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png"
				: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" + z + "/" + y + "/" + x;
	}

	/** Texture if already loaded; never starts a download. */
	public Identifier peek(String style, int z, int x, int y) {
		Entry e = tiles.get(style + "/" + z + "/" + x + "/" + y);
		return e != null && e.status == Status.READY ? e.id : null;
	}

	/** Texture for the tile, or null while it is loading / unavailable. */
	public Identifier get(String style, int z, int x, int y) {
		String key = style + "/" + z + "/" + x + "/" + y;
		Entry e = tiles.get(key);
		if (e != null) {
			if (e.status == Status.READY) return e.id;
			if (e.status == Status.LOADING) return null;
			if (System.currentTimeMillis() - e.failedAt < 15_000) return null;
		}
		if (inFlight.get() >= MAX_IN_FLIGHT) return null;
		Entry entry = e != null ? e : new Entry();
		entry.status = Status.LOADING;
		tiles.put(key, entry);
		inFlight.incrementAndGet();
		String url = url(style, z, x, y);
		POOL.execute(() -> {
			int[] argb = null;
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(15))
						.header("User-Agent", USER_AGENT)
						.build();
				HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
				if (res.statusCode() == 200) {
					argb = decode(res.body());
				}
			} catch (Exception ex) {
				McGpsClient.LOG.debug("Tile {} failed: {}", url, ex.toString());
			}
			int[] pixels = argb;
			Minecraft.getInstance().execute(() -> {
				inFlight.decrementAndGet();
				if (tiles.get(key) != entry) return; // evicted or cleared meanwhile
				if (pixels == null) {
					entry.status = Status.FAILED;
					entry.failedAt = System.currentTimeMillis();
					return;
				}
				NativeImage image = new NativeImage(256, 256, false);
				for (int py = 0; py < 256; py++) {
					for (int px = 0; px < 256; px++) {
						image.setPixel(px, py, pixels[py * 256 + px] | 0xFF000000);
					}
				}
				Identifier id = Identifier.fromNamespaceAndPath(McGpsClient.MOD_ID, "tile/" + (seq++));
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "mcgps tile " + key, image));
				entry.id = id;
				entry.status = Status.READY;
				evict();
			});
		});
		return null;
	}

	/** PNG or JPEG to 256x256 ARGB, via the STB decoder that ships with the game. */
	private static int[] decode(byte[] bytes) {
		ByteBuffer src = MemoryUtil.memAlloc(bytes.length);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			src.put(bytes).flip();
			IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
			ByteBuffer rgba = STBImage.stbi_load_from_memory(src, w, h, c, 4);
			if (rgba == null) return null;
			try {
				if (w.get(0) != 256 || h.get(0) != 256) return null;
				int[] out = new int[256 * 256];
				for (int i = 0; i < out.length; i++) {
					int r = rgba.get(i * 4) & 0xFF, g = rgba.get(i * 4 + 1) & 0xFF, b = rgba.get(i * 4 + 2) & 0xFF;
					out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
				}
				return out;
			} finally {
				STBImage.stbi_image_free(rgba);
			}
		} finally {
			MemoryUtil.memFree(src);
		}
	}

	private void evict() {
		Iterator<Map.Entry<String, Entry>> it = tiles.entrySet().iterator();
		while (tiles.size() > MAX_TILES && it.hasNext()) {
			Entry e = it.next().getValue();
			if (e.status == Status.LOADING) continue;
			if (e.id != null) Minecraft.getInstance().getTextureManager().release(e.id);
			it.remove();
		}
	}

	public void clear() {
		for (Entry e : tiles.values()) {
			if (e.id != null) Minecraft.getInstance().getTextureManager().release(e.id);
		}
		tiles.clear();
	}
}

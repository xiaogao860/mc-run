package dev.mcgps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saved in config/mcgps.json. */
public final class GeoConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Real-world point (WGS-84) that block (anchorX, anchorZ) maps to. Default: Tiananmen Square. */
	public double anchorLat = 39.9027218666;
	public double anchorLon = 116.3914413539;
	public double anchorX = 0;
	public double anchorZ = 0;
	/** Meters on Earth per block. */
	public double scale = 1.0;
	/** Clockwise degrees between Minecraft north (-Z) and true north. */
	public double rotation = 0;
	/** Max phone speed in km/h; 0 means follow instantly (teleports jump). */
	public double maxSpeedKmh = 0;
	/** Position updates per second sent to the phone. */
	public double sendHz = 4;
	/** "scale8": Nether coordinates x8; "pause": hold position in the Nether. */
	public String nether = "scale8";
	public boolean hud = true;
	/** "satellite" or "street". */
	public String mapStyle = "satellite";
	/** Python 3 used to create the worker runtime; empty means auto-detect. */
	public String python = "";

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcgps.json");
	}

	public static GeoConfig load() {
		Path p = path();
		if (Files.exists(p)) {
			try {
				GeoConfig c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), GeoConfig.class);
				if (c != null) {
					c.sanitize();
					return c;
				}
			} catch (Exception e) {
				McGpsClient.LOG.warn("Bad config {}, using defaults", p, e);
			}
		}
		GeoConfig c = new GeoConfig();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			McGpsClient.LOG.warn("Cannot save config", e);
		}
	}

	private void sanitize() {
		if (!Double.isFinite(scale) || scale <= 0) scale = 1;
		if (!Double.isFinite(rotation)) rotation = 0;
		if (!Double.isFinite(maxSpeedKmh) || maxSpeedKmh < 0) maxSpeedKmh = 0;
		if (!Double.isFinite(sendHz) || sendHz < 0.5 || sendHz > 10) sendHz = 4;
		if (!"pause".equals(nether)) nether = "scale8";
		if (!"street".equals(mapStyle)) mapStyle = "satellite";
		if (python == null) python = "";
		if (!Double.isFinite(anchorLat) || Math.abs(anchorLat) > 85) anchorLat = 39.9027218666;
		if (!Double.isFinite(anchorLon) || Math.abs(anchorLon) > 180) anchorLon = 116.3914413539;
		if (!Double.isFinite(anchorX)) anchorX = 0;
		if (!Double.isFinite(anchorZ)) anchorZ = 0;
	}
}

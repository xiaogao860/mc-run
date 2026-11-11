package dev.mcgps;

/** Spherical-earth helpers, same formulas as iphone-location-controller's motion.py. */
public final class GeoMath {
	public static final double R = 6371008.8;

	private GeoMath() {}

	public record LatLon(double lat, double lon) {
		public boolean valid() {
			return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 85 && Math.abs(lon) <= 180;
		}
	}

	public static double distance(LatLon a, LatLon b) {
		double a1 = Math.toRadians(a.lat), b1 = Math.toRadians(a.lon);
		double a2 = Math.toRadians(b.lat), b2 = Math.toRadians(b.lon);
		double h = Math.pow(Math.sin((a2 - a1) / 2), 2)
				+ Math.cos(a1) * Math.cos(a2) * Math.pow(Math.sin((b2 - b1) / 2), 2);
		return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
	}

	/** Initial bearing from a to b, radians clockwise from north. */
	public static double bearing(LatLon a, LatLon b) {
		double lat1 = Math.toRadians(a.lat), lat2 = Math.toRadians(b.lat);
		double dl = Math.toRadians(((b.lon - a.lon + 540) % 360) - 180);
		return Math.atan2(Math.sin(dl) * Math.cos(lat2),
				Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dl));
	}

	public static LatLon destination(LatLon p, double meters, double bearing) {
		double lat = Math.toRadians(p.lat), lon = Math.toRadians(p.lon);
		double d = meters / R;
		double a = Math.asin(Math.max(-1, Math.min(1,
				Math.sin(lat) * Math.cos(d) + Math.cos(lat) * Math.sin(d) * Math.cos(bearing))));
		double b = lon + Math.atan2(Math.sin(bearing) * Math.sin(d) * Math.cos(lat),
				Math.cos(d) - Math.sin(lat) * Math.sin(a));
		double outLat = Math.max(-85, Math.min(85, Math.toDegrees(a)));
		return new LatLon(outLat, ((Math.toDegrees(b) + 540) % 360) - 180);
	}

	/** Minecraft block X/Z (+X east, +Z south) to WGS-84. */
	public static LatLon toGeo(GeoConfig c, double x, double z) {
		double east = (x - c.anchorX) * c.scale;
		double north = -(z - c.anchorZ) * c.scale;
		double meters = Math.hypot(east, north);
		LatLon anchor = new LatLon(c.anchorLat, c.anchorLon);
		if (meters < 1e-6) return anchor;
		return destination(anchor, meters, Math.atan2(east, north) + Math.toRadians(c.rotation));
	}

	/** Moves from toward to by at most maxMeters along the great circle. */
	public static LatLon approach(LatLon from, LatLon to, double maxMeters) {
		double d = distance(from, to);
		if (d <= maxMeters || d < 1e-6) return to;
		return destination(from, maxMeters, bearing(from, to));
	}

	// Web Mercator, in tile units at the given zoom.
	public static double tileX(double lon, int zoom) {
		return (lon + 180) / 360 * (1 << zoom);
	}

	public static double tileY(double lat, int zoom) {
		double r = Math.toRadians(lat);
		return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << zoom);
	}

	public static double lonOf(double tileX, int zoom) {
		return tileX / (1 << zoom) * 360 - 180;
	}

	public static double latOf(double tileY, int zoom) {
		double n = Math.PI - 2 * Math.PI * tileY / (1 << zoom);
		return Math.toDegrees(Math.atan(Math.sinh(n)));
	}
}

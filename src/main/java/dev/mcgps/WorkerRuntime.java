package dev.mcgps;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Python runtime for the phone worker, kept in .minecraft/mcgps/.
 * First connect creates a venv and installs the same pymobiledevice3 version
 * that iphone-location-controller ships.
 */
public final class WorkerRuntime {
	public static final String PMD3 = "pymobiledevice3==11.27.2";
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	private WorkerRuntime() {}

	public static Path home() {
		return FabricLoader.getInstance().getGameDir().resolve("mcgps");
	}

	private static Path venv() {
		return home().resolve("runtime");
	}

	private static Path venvPython() {
		return WINDOWS ? venv().resolve("Scripts").resolve("python.exe") : venv().resolve("bin").resolve("python3");
	}

	private static Path marker() {
		return venv().resolve(".mcgps-ready");
	}

	public static boolean installed() {
		try {
			return Files.isRegularFile(venvPython()) && PMD3.equals(Files.readString(marker()).trim());
		} catch (IOException e) {
			return false;
		}
	}

	/** Makes sure the runtime exists; returns the command that starts the worker. */
	public static List<String> prepare(String configuredPython, Consumer<String> status, Cancel cancel) throws Exception {
		// Development only: -Dmcrun.worker=/path/script.py stands in for the phone (used to film demos).
		String stand = System.getProperty("mcrun.worker");
		if (stand != null) return List.of("/usr/bin/python3", "-u", stand);
		Path script = home().resolve("backend.py");
		Files.createDirectories(home());
		try (InputStream in = WorkerRuntime.class.getResourceAsStream("/mcgps/backend.py")) {
			if (in == null) throw new IOException("模组内缺少 backend.py");
			Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
		}
		if (!installed()) {
			List<String> base = findPython(configuredPython);
			status.accept("首次使用：正在创建 Python 运行环境…");
			deleteTree(venv());
			List<String> cmd = new ArrayList<>(base);
			cmd.addAll(List.of("-m", "venv", venv().toString()));
			run(cmd, null, cancel, 300);
			status.accept("首次使用：正在安装手机通信组件（约 1～3 分钟）…");
			run(List.of(venvPython().toString(), "-m", "pip", "install", "--disable-pip-version-check",
					"--no-input", PMD3), status, cancel, 900);
			Files.writeString(marker(), PMD3);
		}
		return List.of(venvPython().toString(), "-u", script.toString());
	}

	public static void reinstall() throws IOException {
		deleteTree(venv());
	}

	private static List<String> findPython(String configured) throws Exception {
		List<List<String>> candidates = new ArrayList<>();
		if (configured != null && !configured.isBlank()) candidates.add(List.of(configured.trim()));
		if (WINDOWS) {
			candidates.add(List.of("py", "-3"));
			candidates.add(List.of("python"));
			candidates.add(List.of("python3"));
		} else {
			// Same interpreter the original macOS build uses first.
			candidates.add(List.of("/usr/bin/python3"));
			candidates.add(List.of("/opt/homebrew/bin/python3"));
			candidates.add(List.of("/usr/local/bin/python3"));
			candidates.add(List.of("python3"));
		}
		for (List<String> c : candidates) {
			List<String> cmd = new ArrayList<>(c);
			cmd.addAll(List.of("-c", "import sys,venv;sys.exit(0 if sys.version_info>=(3,9) else 1)"));
			try {
				Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				p.getInputStream().readAllBytes();
				if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) return c;
				p.destroyForcibly();
			} catch (IOException ignored) {
				// Not installed; try the next one.
			}
		}
		throw new IOException(WINDOWS
				? "没有找到 Python 3.9 以上版本。请从 python.org 安装 Python 3.11（勾选 Add to PATH），或用 /mcrun python <路径> 指定。"
				: "没有找到可用的 Python 3。请先运行 xcode-select --install 安装命令行工具，或用 /mcrun python <路径> 指定。");
	}

	private static void run(List<String> cmd, Consumer<String> status, Cancel cancel, int timeoutSeconds) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		pb.environment().put("PYTHONIOENCODING", "utf-8");
		pb.environment().put("PIP_NO_INPUT", "1");
		Process p = pb.start();
		cancel.attach(p);
		ArrayDeque<String> tail = new ArrayDeque<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (tail.size() >= 12) tail.removeFirst();
				tail.addLast(line);
				if (status != null && line.startsWith("Collecting ")) {
					status.accept("正在下载 " + line.substring(11).split("[ =<>!]")[0] + "…");
				}
			}
		}
		if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IOException("安装超时");
		}
		cancel.attach(null);
		if (cancel.cancelled()) throw new InterruptedException("已取消");
		if (p.exitValue() != 0) {
			deleteTree(venv());
			throw new IOException("安装失败：" + String.join("\n", tail));
		}
	}

	static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var walk = Files.walk(root)) {
			for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** Lets disconnect stop a running install step. */
	public static final class Cancel {
		private volatile boolean cancelled;
		private volatile Process current;

		void attach(Process p) {
			current = p;
			if (cancelled && p != null) p.destroyForcibly();
		}

		public void cancel() {
			cancelled = true;
			Process p = current;
			if (p != null) p.destroyForcibly();
		}

		public boolean cancelled() {
			return cancelled;
		}
	}
}

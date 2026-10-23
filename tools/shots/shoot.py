"""
Screenshots and an animation of Allay for the READMEs, Modrinth, and allay.chat, taken in a real game: a local Paper
with the plugin as built, stub.py instead of the backend (its answers are fixtures/*.json), the vanilla client from
the Minecraft launcher, offline, and ShotDriver (Driver.java) to type for the player. macOS only: screencapture takes
the game's window and nothing else on the screen.

    ./gradlew build
    python3 -I tools/shots/shoot.py [--langs en,ru,es] [--keep ru]

Writes media/screenshots/<lang>/: consent.webp, answer.webp, say.webp, memory.webp, privacy.webp, and
status.gif with status.mp4 and status.webp, its last frame. --keep leaves the server, the stub, and the client in that language running afterwards, to
press the buttons by hand; stop them with --stop.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT = REPO / "media" / "screenshots"
WORK = pathlib.Path.home() / ".cache" / "allay-shots"
MC = pathlib.Path.home() / "Library" / "Application Support" / "minecraft"
GAME = "26.3"
SERVER_PORT = 25599
STUB_PORT = 28111
PLAYER = "Steve"
LOCALES = {"en": "en_us", "ru": "ru_ru", "es": "es_es"}
# The window in logical pixels. The GUI scale counts the Retina screen's own pixels: 5 makes the GUI 640 wide, large
# enough to read, and the windows (400) fit. The chat is narrowed to 70%, so the status above the hotbar stays clear of it.
WIDTH, HEIGHT, SCALE, CHAT_WIDTH = 1600, 1200, 5, 0.7
# Where the player stands for the shots: x y z yaw pitch, in the world of SEED, on a barrier block nobody sees.
SEED = "8091867987493326313"
VIEW = "40.5 118 110.5 10 22"
FLOOR = "40 117 110"

# Crops as fractions of the window: left, top, right, bottom.
CHAT = (0.005, 0.52, 0.67, 0.99)
# The animation keeps to the chat and the hotbar: the world above them moves (clouds, mobs) and costs a GIF its size.
STATUS = (0.005, 0.52, 0.80, 0.99)
# Each window as tall as it is.
DIALOGS = {"consent": (0.17, 0.0, 0.83, 0.70), "memory": (0.17, 0.0, 0.83, 0.62), "privacy": (0.17, 0.0, 0.83, 1.0)}
# What the player holds, so the hotbar looks lived in; the coal and sticks are what the torches answer counts.
HOTBAR = ["torch 24", "diamond_pickaxe", "iron_sword", "coal 7", "stick 4", "bread 12", "compass", "oak_log 48", "crafting_table"]
# The chat shows a line for ten seconds; a scene waits that long first, so only its own lines show.
FADE = 10.5


def fixture(name: str) -> dict:
    return json.loads((HERE / "fixtures" / f"{name}.json").read_text())


def log(text: str):
    print(f"[shots] {text}", flush=True)


# Setup

def paper_jar() -> pathlib.Path:
    cached = sorted((pathlib.Path.home() / ".cache" / "paper-smoke").glob(f"paper-{GAME}-*.jar"), key=lambda p: int(p.stem.rsplit("-", 1)[1]))
    if not cached:
        sys.exit("No Paper jar in ~/.cache/paper-smoke: run .claude/scripts/paper_smoke.py once first")
    return cached[-1]


def driver_jar(out: pathlib.Path):
    api = next(pathlib.Path.home().glob(f".gradle/caches/modules-2/files-2.1/org.spigotmc/spigot-api/{GAME}-R0.1-SNAPSHOT/*/spigot-api-{GAME}-R0.1-SNAPSHOT.jar"))
    with tempfile.TemporaryDirectory() as classes:
        subprocess.run(["javac", "-proc:none", "-cp", str(api), "-d", classes, str(HERE / "Driver.java")], check=True)
        pathlib.Path(classes, "plugin.yml").write_text(f"name: ShotDriver\nmain: shots.Driver\nversion: 1\napi-version: '{GAME}'\n")
        subprocess.run(["jar", "cf", str(out), "-C", classes, "."], check=True)


def prepare_server() -> pathlib.Path:
    server = WORK / "server"
    plugins = server / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    plugin = REPO / "spigot" / "build" / "libs" / "allay-spigot-dev.jar"
    if not plugin.exists():
        sys.exit("Build the plugin first: ./gradlew build")
    shutil.copy(paper_jar(), server / "paper.jar")
    shutil.copy(plugin, plugins / "allay-spigot-dev.jar")
    driver_jar(plugins / "ShotDriver.jar")
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text("\n".join([
        "online-mode=false", f"server-port={SERVER_PORT}", "white-list=false", "difficulty=peaceful", "spawn-protection=0",
        f"level-seed={SEED}", "motd=Allay", "view-distance=12", "",
    ]))
    (plugins / "Allay").mkdir(exist_ok=True)
    (plugins / "Allay" / "config.yml").write_text(f'server:\n  url: "http://127.0.0.1:{STUB_PORT}"\n  name: "Moonlands"\n')
    return server


def start(args: list[str], cwd: pathlib.Path, log_file: pathlib.Path) -> subprocess.Popen:
    return subprocess.Popen(args, cwd=cwd, stdout=open(log_file, "w"), stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)


def wait_for(path: pathlib.Path, text: str, since: int = 0, timeout: float = 240):
    end = time.time() + timeout
    while time.time() < end:
        if path.exists() and text in path.read_text(errors="ignore")[since:]:
            return
        time.sleep(0.5)
    sys.exit(f"Timed out waiting for “{text}” in {path}")


def java25() -> str:
    return subprocess.run(["/usr/libexec/java_home", "-v", "25"], capture_output=True, text=True, check=True).stdout.strip() + "/bin/java"


def start_server(server: pathlib.Path):
    start([sys.executable, "-I", str(HERE / "stub.py"), str(STUB_PORT)], HERE, WORK / "stub.log")
    start([java25(), "-Xmx3G", "-jar", "paper.jar", "--nogui"], server, WORK / "server.log")
    wait_for(WORK / "server.log", "Done (")
    log("server is up")


# The client

def client_args(game: pathlib.Path) -> list[str]:
    version = json.loads((MC / "versions" / GAME / f"{GAME}.json").read_text())

    def allowed(rules) -> bool:
        ok = not rules
        for rule in rules or []:
            os_ = rule.get("os", {})
            match = "features" not in rule and (not os_.get("name") or os_["name"] == "osx") and (not os_.get("arch") or os_["arch"] in ("arm64", "aarch64"))
            if match:
                ok = rule["action"] == "allow"
        return ok

    jars = [str(MC / "libraries" / lib["downloads"]["artifact"]["path"]) for lib in version["libraries"] if allowed(lib.get("rules")) and lib.get("downloads", {}).get("artifact")]
    jars.append(str(MC / "versions" / GAME / f"{GAME}.jar"))
    java = next((MC / "runtime").glob("*/*/*/jre.bundle/Contents/Home/bin/java"))
    natives = game / "natives"
    natives.mkdir(parents=True, exist_ok=True)
    return [
        str(java), "-XstartOnFirstThread", "-Xmx2G", "--enable-native-access=ALL-UNNAMED",
        f"-Djava.library.path={natives}", f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}", "-cp", ":".join(jars), version["mainClass"],
        "--username", PLAYER, "--version", GAME, "--gameDir", str(game), "--assetsDir", str(MC / "assets"), "--assetIndex", version["assetIndex"]["id"],
        "--uuid", "0" * 32, "--accessToken", "0", "--versionType", "release", "--width", str(WIDTH), "--height", str(HEIGHT),
        "--quickPlayMultiplayer", f"localhost:{SERVER_PORT}",
    ]


def start_client(lang: str):
    game = WORK / "client"
    game.mkdir(parents=True, exist_ok=True)
    (game / "options.txt").write_text("\n".join([
        f"lang:{LOCALES[lang]}", f"guiScale:{SCALE}", "onboardAccessibility:false", "skipMultiplayerWarning:true", "joinedFirstServer:true",
        "tutorialStep:none", "narrator:0", "soundCategory_master:0.0", f"chatWidth:{CHAT_WIDTH}", "chatHeightFocused:1.0", "chatHeightUnfocused:1.0", "chatOpacity:1.0",
        "textBackgroundOpacity:0.5", "renderDistance:12", "fov:0.25", "",
    ]))
    _window.clear()
    since = len((WORK / "server.log").read_text(errors="ignore"))
    start(client_args(game), game, WORK / f"client-{lang}.log")
    wait_for(WORK / "server.log", f"{PLAYER} joined the game", since)
    # The insecure chat toast and the world fading in.
    time.sleep(8)
    log(f"{lang}: the client joined")


def stop_client():
    subprocess.run(["pkill", "-f", f"gameDir {WORK}/client"])
    time.sleep(3)


def stop_all():
    stop_client()
    subprocess.run(["pkill", "-f", "paper.jar --nogui"])
    subprocess.run(["pkill", "-f", str(HERE / "stub.py")])


# Playing

def drive(*lines: str, wait: float = 0.0):
    """Hands lines to ShotDriver and returns once it ran them, plus [wait] seconds."""
    file = WORK / "server" / "plugins" / "driver.txt"
    file.write_text("\n".join(lines))
    end = time.time() + 20
    while file.exists() and time.time() < end:
        time.sleep(0.1)
    time.sleep(wait)


def stub(path: str, body: dict):
    request = urllib.request.Request(f"http://127.0.0.1:{STUB_PORT}/shots/{path}", json.dumps(body).encode(), {"Content-Type": "application/json"})
    urllib.request.urlopen(request).close()


def consent(given: bool):
    stub("consent", {"given": given})


_window: dict[str, str] = {}


def window() -> str:
    """The game window's number, looked up once per client: the lookup costs more than a frame."""
    if "id" not in _window:
        found = subprocess.run(["swift", str(HERE / "winid.swift")], capture_output=True, text=True).stdout.strip()
        if not found:
            sys.exit("The game window isn't there")
        _window["id"] = found
    return _window["id"]


def grab(path: pathlib.Path, tries: int = 10) -> bool:
    """The game's window only; screencapture fails while the window is on another desktop, so it tries again."""
    for _ in range(tries):
        if subprocess.run(["screencapture", "-x", "-o", "-l", window(), str(path)]).returncode == 0:
            return True
        time.sleep(0.5)
    if tries > 1:
        sys.exit("Couldn't capture the game's window: keep it on the current desktop")
    return False


def crop(source: pathlib.Path, target: pathlib.Path, box: tuple, width: int):
    """Cuts [box] out of a capture and scales it to [width]; the window's title bar is left out."""
    w, h = (int(v) for v in subprocess.run(["magick", "identify", "-format", "%w %h", str(source)], capture_output=True, text=True, check=True).stdout.split())
    bar = round(h * 28 / (HEIGHT + 28))
    inner = h - bar
    x0, y0, x1, y1 = round(w * box[0]), bar + round(inner * box[1]), round(w * box[2]), bar + round(inner * box[3])
    target.parent.mkdir(parents=True, exist_ok=True)
    args = ["magick", str(source), "-crop", f"{x1 - x0}x{y1 - y0}+{x0}+{y0}", "+repage", "-resize", f"{width}x"]
    if target.suffix == ".webp":
        args += ["-quality", "90"]
    subprocess.run(args + [str(target)], check=True)


def shot(lang: str, name: str, box: tuple, width: int = 1400):
    raw = WORK / "raw" / lang / f"{name}.png"
    raw.parent.mkdir(parents=True, exist_ok=True)
    grab(raw)
    crop(raw, OUT / lang / f"{name}.webp", box, width)
    log(f"{lang}: {name}")


def film(lang: str, name: str, start_lines: list[str], seconds: float):
    """Grabs the window as fast as screencapture goes while [start_lines] play out; [render] makes the files."""
    frames = WORK / "raw" / lang / name
    shutil.rmtree(frames, ignore_errors=True)
    frames.mkdir(parents=True)
    taken: list[float] = []
    began = time.time()
    threading.Thread(target=drive, args=start_lines, daemon=True).start()
    while time.time() - began < seconds:
        # A frame the screen didn't give is skipped; the one before it simply stays longer.
        if grab(frames / f"{len(taken):04d}.png", tries=1):
            taken.append(time.time() - began)
    (frames / "times.json").write_text(json.dumps(taken))
    render(lang, name)
    log(f"{lang}: {name} ({len(taken)} frames in {seconds:.0f} s)")


def render(lang: str, name: str):
    """
    The animation from its raw frames: a GIF for the READMEs, an MP4 for the site, its last frame as the video's
    poster, and that frame again as the answer's still. Each frame stays until the next was taken; the last holds
    for 2.5 seconds, so the answer can be read.
    """
    frames = WORK / "raw" / lang / name
    times = json.loads((frames / "times.json").read_text())
    durations = [later - earlier for earlier, later in zip(times, times[1:])] + [2.5]
    cropped = []
    for n in range(len(times)):
        target = frames / f"c{n:04d}.png"
        crop(frames / f"{n:04d}.png", target, STATUS, 1000)
        cropped.append(target)
    out = OUT / lang
    out.mkdir(parents=True, exist_ok=True)
    # Only what changed goes into each GIF frame (within 4%, so the world's flicker doesn't count): 0.6 MB, not 30.
    sequence = [arg for path, seconds in zip(cropped, durations) for arg in ("-delay", str(round(seconds * 100)), str(path))]
    subprocess.run(["magick", *sequence, "-resize", "760x", "-dither", "None", "-colors", "128", "-fuzz", "4%", "-layers", "OptimizeTransparency", "-loop", "0", str(out / f"{name}.gif")], check=True)
    listing = frames / "frames.txt"
    listing.write_text("".join(f"file '{path}'\nduration {seconds:.3f}\n" for path, seconds in zip(cropped, durations)) + f"file '{cropped[-1]}'\n")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(listing), "-vf", "fps=24,format=yuv420p,scale=trunc(iw/2)*2:trunc(ih/2)*2",
                    "-c:v", "libx264", "-crf", "24", "-movflags", "+faststart", str(out / f"{name}.mp4")], check=True)
    subprocess.run(["magick", str(cropped[-1]), "-quality", "90", str(out / f"{name}.webp")], check=True)
    crop(frames / f"{len(times) - 1:04d}.png", out / "answer.webp", CHAT, 1400)


def remake(lang: str):
    """Crops and encodes again from what the last run grabbed, after a change to the boxes or the encoding."""
    for name, box in {"consent": DIALOGS["consent"], "say": CHAT, "memory": DIALOGS["memory"], "privacy": DIALOGS["privacy"]}.items():
        crop(WORK / "raw" / lang / f"{name}.png", OUT / lang / f"{name}.webp", box, 1400)
    render(lang, "status")
    log(f"{lang}: remade")


def scenes(lang: str):
    torches = fixture("torches")
    diamonds = fixture("diamonds")
    memories = fixture("memories")
    drive(
        "!respawn", f"@gamemode survival {PLAYER}", "@time set 6000", "@weather clear", f"@clear {PLAYER}",
        *[f"@give {PLAYER} {item}" for item in HOTBAR], f"@setblock {FLOOR} barrier", f"@tp {PLAYER} {VIEW}", "allay hints off", wait=FADE,
    )

    stub("reset", {})

    # Agreeing first: the age (the country of 127.0.0.1 is unknown), then the notice.
    consent(False)
    drive(f"ask {torches['question'][lang]}", wait=3)
    drive("allay privacy age 18", wait=2)
    shot(lang, "consent", DIALOGS["consent"])
    drive("allay privacy agree", wait=10)

    # The same question again, agreed: the live status, then the answer with its buttons.
    time.sleep(FADE)
    film(lang, "status", [f"ask {torches['question'][lang]}"], 10.5)

    # A message to send: the answer with Send, and the line the button sends.
    time.sleep(FADE)
    drive(f"ask {diamonds['question'][lang]}", wait=6.5)
    drive(f"!chat {diamonds['say'][lang]}", wait=1.5)
    shot(lang, "say", CHAT)

    # The windows: memory, then privacy.
    drive(*[f"allay memory {how} {text}" for how, text in memories["memories"][lang]], wait=2)
    drive("allay memory", wait=2)
    shot(lang, "memory", DIALOGS["memory"])
    drive("allay privacy", wait=2)
    shot(lang, "privacy", DIALOGS["privacy"])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--langs", default="en,ru,es")
    parser.add_argument("--keep", help="leave the game running in this language afterwards")
    parser.add_argument("--stop", action="store_true", help="stop what --keep left running")
    parser.add_argument("--remake", action="store_true", help="crop and encode again from the last run's raw captures")
    args = parser.parse_args()
    if args.stop:
        return stop_all()
    if args.remake:
        for lang in args.langs.split(","):
            remake(lang)
        return
    stop_all()
    WORK.mkdir(parents=True, exist_ok=True)
    start_server(prepare_server())
    try:
        for lang in args.langs.split(","):
            start_client(lang)
            scenes(lang)
            stop_client()
        if args.keep:
            consent(True)
            start_client(args.keep)
            drive(f"@gamemode creative {PLAYER}", f"@op {PLAYER}")
            log(f"left running in {args.keep}: python3 -I tools/shots/shoot.py --stop to end it")
            return
    except BaseException:
        stop_all()
        raise
    stop_all()


if __name__ == "__main__":
    main()

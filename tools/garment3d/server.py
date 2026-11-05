"""Loopback-only sample viewer. Serves a small, explicit set of local assets."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit
import argparse

ROOT = Path(__file__).resolve().parent
STATIC = {"/": "index.html", "/index.html": "index.html", "/style.css": "style.css",
          "/viewer.mjs": "viewer.mjs", "/viewer-lifecycle.mjs": "viewer-lifecycle.mjs", "/rig.mjs": "rig.mjs", "/motion-player.mjs": "motion-player.mjs"}
STATIC.update({f"/{name}": name for name in ("live-view.mjs", "live-driver.mjs", "cloth.mjs", "cloth-material.mjs")})
ASSETS = {"/assets/blue-tee.glb": "blue-tee.glb", "/assets/source.png": "source.png",
          "/assets/sample.json": "sample.json", "/assets/motion.json": "motion.json", "/assets/motion.bin": "motion.bin"}


def resolve_asset(url):
    route = unquote(urlsplit(url).path)
    if route in STATIC:
        return ROOT / STATIC[route]
    if route in ASSETS:
        return ROOT / ".local" / ASSETS[route]
    if route in ("/vendor/three.module.js", "/vendor/three.core.js"):
        return ROOT / "node_modules/three/build" / route.rsplit("/", 1)[-1]
    prefix = "/vendor/addons/"
    if route.startswith(prefix) and route.endswith(".js"):
        relative = route[len(prefix):]
        # Apply the same URL rules on Windows and Linux, including drive paths.
        if any(character in relative for character in ("\\", ":", "\x00")):
            return None
        base = (ROOT / "node_modules/three/examples/jsm").resolve()
        candidate = (base / relative).resolve()
        if candidate.is_relative_to(base):
            return candidate
    return None


class Handler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, ".mjs": "text/javascript",
                      ".js": "text/javascript", ".glb": "model/gltf-binary"}

    def do_GET(self):
        self._serve(False)

    def do_HEAD(self):
        self._serve(True)

    def _serve(self, head):
        target = resolve_asset(self.path)
        if target is None or not target.is_file():
            self.send_error(404)
            return
        content = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", self.guess_type(str(target)))
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        if not head:
            self.wfile.write(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8022)
    args = parser.parse_args()
    print(f"Garment sample: http://127.0.0.1:{args.port}/", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()

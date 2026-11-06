import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("sample_server", Path(__file__).resolve().parents[1] / "server.py")
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


class RoutesTest(unittest.TestCase):
    def test_known_assets_resolve(self):
        for route in ("/", "/style.css", "/viewer.mjs", "/viewer-lifecycle.mjs", "/rig.mjs", "/motion-player.mjs", "/assets/blue-tee.glb", "/assets/motion.bin",
                      "/vendor/three.module.js", "/vendor/addons/loaders/GLTFLoader.js"):
            self.assertIsNotNone(server.resolve_asset(route))

    def test_no_directory_listing_or_secret_routes(self):
        for route in ("/.env", "/.local/", "/node_modules/", "/assets/../.env",
                      "/vendor/addons/../../../../backend/app.py", "/vendor/addons/%2e%2e/%2e%2e/%2e%2e/secret.js",
                      "/vendor/addons/C:/private.js", "/assets/source.png/../sample.json"):
            self.assertIsNone(server.resolve_asset(route), route)


if __name__ == "__main__":
    unittest.main()

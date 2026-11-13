package com.smartfitting.mirror.garment3d

import org.junit.Assert.*
import org.junit.Test

class Garment3dPolicyTest {
    @Test fun acceptsOnlyExplicitPackagedResourcesWithCorrectModuleMime() {
        assertTrue(Garment3dPolicy.document(Garment3dPolicy.ENTRY))
        Garment3dPolicy.resources.forEach { (path,_) ->
            assertEquals(path,Garment3dPolicy.resource("${Garment3dPolicy.ORIGIN}${Garment3dPolicy.PREFIX}$path"))
        }
        assertEquals("text/javascript",Garment3dPolicy.resources["viewer.mjs"])
    }
    @Test fun deniesRemoteFileContentTraversalAndOriginLookalikes() {
        for (url in listOf(
            "https://example.com/garment3d/index.html", "http://appassets.androidplatform.net/garment3d/index.html",
            "https://appassets.androidplatform.net.evil.test/garment3d/index.html",
            "https://appassets.androidplatform.net@evil.test/garment3d/index.html",
            "https://user@appassets.androidplatform.net/garment3d/index.html",
            "https://appassets.androidplatform.net:444/garment3d/index.html",
            "file:///android_asset/garment3d/index.html", "content://private/photo",
            "https://appassets.androidplatform.net/garment3d/../secret",
            "https://appassets.androidplatform.net/garment3d/%2e%2e/secret",
            "https://appassets.androidplatform.net/garment3d/assets/motion.bin?remote=https://evil.test",
            "https://appassets.androidplatform.net/garment3d/unknown.js",
        )) assertNull(url,Garment3dPolicy.resource(url))
        assertFalse(Garment3dPolicy.document("${Garment3dPolicy.ORIGIN}/garment3d/viewer.mjs"))
    }
}

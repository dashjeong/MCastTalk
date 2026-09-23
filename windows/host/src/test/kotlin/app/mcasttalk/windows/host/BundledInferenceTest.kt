package app.mcasttalk.windows.host

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BundledInferenceTest {
    private fun fixture(run: (Path,Path)->Unit) {
        val root=Files.createTempDirectory("mcast-bundle-test")
        try {
            val bundle=Files.createDirectory(root.resolve("bundle"));val data=root.resolve("data")
            WorkspaceSetup.initialize(data)
            Files.writeString(bundle.resolve("asset-hashes.properties"),"model.bin="+"0".repeat(64)+"\n")
            Files.writeString(bundle.resolve("model.txt"),"test fixture, not model weights")
            val hashes=listOf("asset-hashes.properties","model.txt").joinToString("\n") { name ->
                name+"="+MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bundle.resolve(name))).joinToString(""){"%02x".format(it)}
            }
            Files.writeString(bundle.resolve("manifest.properties"),hashes)
            run(bundle,data)
        }finally{root.toFile().deleteRecursively()}
    }
    @Test fun copiesBundleAndWritesLocalConfiguration()=fixture { bundle,data ->
        BundledInference.install(bundle,data)
        assertTrue(Files.exists(data.resolve("config/inference.properties")))
        assertTrue(Files.readString(data.resolve("config/meeting-engine-0.4.1.json")).contains("\"backend\":\"auto\""))
        assertEquals(Files.readString(bundle.resolve("model.txt")),Files.readString(data.resolve("packages/offline-0.4.1/model.txt")))
    }
    @Test fun tamperedSourceFailsBeforeEnablingInference()=fixture { bundle,data ->
        Files.writeString(bundle.resolve("model.txt"),"tampered")
        assertThrows(IllegalArgumentException::class.java){BundledInference.install(bundle,data)}
        assertFalse(Files.exists(data.resolve("config/inference.properties")))
    }
    @Test fun existingDifferentUserAssetIsPreserved()=fixture { bundle,data ->
        val asset=data.resolve("packages/offline-0.4.1/model.txt");Files.createDirectories(asset.parent);Files.writeString(asset,"user custom file")
        assertThrows(IllegalArgumentException::class.java){BundledInference.install(bundle,data)}
        assertEquals("user custom file",Files.readString(asset))
    }
    @Test fun traversalManifestIsRejected()=fixture { bundle,data ->
        Files.writeString(bundle.resolve("manifest.properties"),"../outside="+"0".repeat(64))
        assertThrows(IllegalArgumentException::class.java){BundledInference.install(bundle,data)}
    }
    @Test fun cancellationDoesNotActivatePartialBundle()=fixture { bundle,data ->
        assertThrows(IllegalStateException::class.java){BundledInference.install(bundle,data){_,_->error("cancel")}}
        assertFalse(Files.exists(data.resolve("config/inference.properties")))
        BundledInference.install(bundle,data)
        assertTrue(Files.exists(data.resolve("config/inference.properties")))
    }
    private fun worker(bundle:Path):Path {
        val file=bundle.resolve("worker/mcasttalk_worker/meeting_engine.py");Files.createDirectories(file.parent);Files.writeString(file,"# synthetic updated worker")
        val digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString(""){"%02x".format(it)}
        Files.writeString(bundle.resolve("manifest.properties"),"worker/mcasttalk_worker/meeting_engine.py=$digest\n")
        return file
    }
    @Test fun managedWorkerUpgradeDoesNotModifyModelsOrConfiguration()=fixture { bundle,data ->
        worker(bundle);val configured=data.resolve("packages/offline-0.4.1/worker")
        val settings=data.resolve("config/inference.properties");Files.writeString(settings,"synthetic operator configuration")
        assertEquals(bundle.toRealPath().resolve("worker"),BundledInference.workerHome(data,configured,bundle))
        assertEquals("synthetic operator configuration",Files.readString(settings));assertFalse(Files.exists(configured))
    }
    @Test fun customWorkerConfigurationIsNeverReplaced()=fixture { bundle,data ->
        worker(bundle);val custom=data.resolve("custom-worker")
        assertEquals(custom,BundledInference.workerHome(data,custom,bundle))
    }
    @Test fun modifiedBundledWorkerFailsClosed()=fixture { bundle,data ->
        val file=worker(bundle);Files.writeString(file,"tampered")
        assertThrows(IllegalArgumentException::class.java){BundledInference.workerHome(data,data.resolve("packages/offline-0.4.1/worker"),bundle)}
    }
}

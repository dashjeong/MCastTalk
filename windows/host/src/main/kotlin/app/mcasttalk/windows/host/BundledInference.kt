package app.mcasttalk.windows.host

import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import javax.swing.JOptionPane
import javax.swing.ProgressMonitor
import javax.swing.SwingUtilities

/** Install only the versioned, hash-verified offline bundle. Never downloads or overwrites user models. */
internal object BundledInference {
    /** Upgrade only application-owned worker code. Models, operator settings and
     * accounts remain in their existing workspace; custom worker paths are untouched. */
    internal fun workerHome(dataRoot:Path, configured:Path, bundle:Path? =
        System.getProperty("jpackage.app-path")?.let(Path::of)?.parent?.resolve("offline")):Path {
        val managed=dataRoot.resolve("packages/offline-0.4.1/worker").toAbsolutePath().normalize()
        if(configured.toAbsolutePath().normalize()!=managed || bundle==null)return configured
        val manifestFile=bundle.resolve("manifest.properties")
        if(!Files.isRegularFile(manifestFile))return configured
        val manifest=Properties().apply { Files.newBufferedReader(manifestFile).use { load(it) } }
        val names=manifest.stringPropertyNames().filter { it.startsWith("worker/") }
        require("worker/mcasttalk_worker/meeting_engine.py" in names) { "Bundled worker manifest is incomplete" }
        val root=bundle.toRealPath()
        for(name in names) {
            val expected=manifest.getProperty(name)
            require(expected.matches(Regex("[a-f0-9]{64}")) && hash(contained(root,name))==expected) { "Bundled worker checksum mismatch" }
        }
        return root.resolve("worker")
    }

    fun configure(dataRoot: Path) {
        val app = System.getProperty("jpackage.app-path")?.let(Path::of)?.parent ?: return
        val bundle = app.resolve("offline")
        if (!Files.isRegularFile(bundle.resolve("manifest.properties"))) return
        val config = dataRoot.resolve("config/inference.properties")
        if (Files.exists(config)) return // Existing operator configuration owns model selection.
        val ram = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize ?: 0L
        val gib = ram / (1024L*1024*1024)
        check(ram >= 8L*1024*1024*1024) { "Offline models require at least 8 GiB physical RAM; observed $gib GiB. Use a larger host." }
        var monitor: ProgressMonitor? = null
        var accepted = true
        if (!GraphicsEnvironment.isHeadless()) SwingUtilities.invokeAndWait {
            val result = JOptionPane.showConfirmDialog(null,
                "오프라인 음성 인식·번역·합성 모델을 데이터 폴더에 준비합니다.\nPC RAM 약 ${gib} GiB · GPU는 Vulkan 시작 실패 시 CPU로 전환합니다.\n8~16 GiB PC에서는 순차 발화·소규모 회의를 권장합니다.\n첫 모델 준비에는 수 분, 통역에는 수 초 이상의 지연이 생길 수 있습니다.\n\n$dataRoot\n계속하시겠습니까?", "MCastTalk 로컬 모델 준비",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE)
            accepted = result == JOptionPane.OK_OPTION
            if (accepted) monitor = ProgressMonitor(null,"오프라인 모델 검증·배치", "준비 중",0,100).apply { millisToDecideToPopup=0;millisToPopup=0 }
        }
        if (!accepted) throw AdminSetupCancelled()
        try {
            install(bundle, dataRoot) { done, total ->
                check(monitor?.isCanceled != true) { "Model setup cancelled. Verified files are retained for retry." }
                monitor?.let { m -> SwingUtilities.invokeLater { m.setProgress(done*100/total.coerceAtLeast(1));m.setNote("$done / $total 파일 검증") } }
            }
        } finally { monitor?.let { SwingUtilities.invokeLater { it.close() } } }
    }

    internal fun install(bundle: Path, dataRoot: Path, progress: (Int,Int)->Unit = {_,_->}) {
        val manifest = Properties().apply { Files.newBufferedReader(bundle.resolve("manifest.properties")).use { load(it) } }
        val names = manifest.stringPropertyNames().sorted()
        require(names.isNotEmpty()) { "Offline bundle manifest is empty" }
        val sourceRoot = bundle.toRealPath()
        val dataReal = dataRoot.toRealPath()
        require(dataRoot.resolve("packages").toRealPath().startsWith(dataReal)) { "Package directory escaped workspace" }
        val destinationRoot = Files.createDirectories(dataRoot.resolve("packages/offline-0.4.1")).toRealPath()
        require(destinationRoot.startsWith(dataReal)) { "Bundle destination escaped workspace" }
        val totalBytes = names.sumOf { name -> Files.size(contained(sourceRoot,name)) }
        require(Files.getFileStore(destinationRoot).usableSpace > totalBytes + 512L*1024*1024) { "Insufficient free space for offline models" }
        for ((index,name) in names.withIndex()) {
            val expected = manifest.getProperty(name)
            require(expected.matches(Regex("[a-f0-9]{64}"))) { "Invalid asset digest" }
            val source = contained(sourceRoot,name)
            val destination = destinationRoot.resolve(name).normalize()
            require(destination.startsWith(destinationRoot)) { "Asset destination escaped bundle" }
            Files.createDirectories(destination.parent)
            require(destination.parent.toRealPath().startsWith(destinationRoot)) { "Asset destination followed an external link" }
            if (Files.exists(destination)) {
                require(destination.toRealPath().startsWith(destinationRoot) && hash(destination)==expected) {
                    "Existing asset differs: $name. It was preserved; choose a fresh workspace or inspect it."
                }
            } else {
                val partial = Files.createTempFile(destination.parent,"mcast-install-",".partial")
                try {
                    Files.copy(source,partial,StandardCopyOption.REPLACE_EXISTING)
                    require(hash(partial)==expected) { "Bundle checksum mismatch: $name" }
                    // No REPLACE_EXISTING: never overwrite a concurrent/user file.
                    Files.move(partial,destination)
                } finally { Files.deleteIfExists(partial) }
            }
            progress(index+1,names.size)
        }
        val hashes = Properties().apply { Files.newBufferedReader(destinationRoot.resolve("asset-hashes.properties")).use { load(it) } }
        val temp = Files.createDirectories(dataRoot.resolve("temp/inference"))
        restrictDirectoryToOwner(temp)
        val engineFile = dataRoot.resolve("config/meeting-engine-0.4.1.json")
        val engine = mapOf("assetRoot" to destinationRoot.resolve("assets").toString(),"tempRoot" to temp.toString(),
            "threads" to minOf(4,Runtime.getRuntime().availableProcessors()).coerceAtLeast(1),"backend" to "auto",
            "sttModel" to "ggml-small-q5_1.bin","beamSize" to 1,"bestOf" to 1,"audioContext" to 512,
            "sha256" to hashes.stringPropertyNames().associateWith { hashes.getProperty(it) })
        if (!Files.exists(engineFile)) Files.writeString(engineFile,encodeJson(engine),java.nio.file.StandardOpenOption.CREATE_NEW)
        val properties = Properties().apply {
            setProperty("python",destinationRoot.resolve("python/python.exe").toString())
            setProperty("workerHome",destinationRoot.resolve("worker").toString())
            setProperty("config",engineFile.toString())
        }
        Files.newBufferedWriter(dataRoot.resolve("config/inference.properties"),java.nio.file.StandardOpenOption.CREATE_NEW).use {
            properties.store(it,"MCastTalk offline model setup; operator-owned configuration")
        }
    }

    private fun contained(root: Path, name: String): Path {
        require(!name.contains('\\') && !name.contains(':') && name.split('/').none { it in setOf("", ".", "..") }) { "Unsafe asset path" }
        val file = root.resolve(name).normalize()
        require(file.startsWith(root) && file.toRealPath().startsWith(root) && Files.isRegularFile(file)) { "Unsafe asset file" }
        return file
    }
    private fun hash(path: Path): String {
        val digest=MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input -> val buffer=ByteArray(1024*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun restrictDirectoryToOwner(dir: Path) {
    val view=Files.getFileAttributeView(dir,java.nio.file.attribute.AclFileAttributeView::class.java)
    if(view!=null) view.acl=listOf(java.nio.file.attribute.AclEntry.newBuilder()
        .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(view.owner)
        .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission::class.java))
        .setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT).build())
    else Files.setPosixFilePermissions(dir,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
}

package app.mcasttalk.windows.host
import java.nio.file.Path

/** Test-only bundle installation into an already initialized throwaway workspace. */
object BundleFixtureMain {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2)
        val data=Path.of(args[1]).toAbsolutePath().normalize()
        val allowed=Path.of(System.getProperty("user.dir"),".run").toAbsolutePath().normalize()
        require(data.startsWith(allowed)&&data!=allowed)
        DataRootGate.requireInitialized(data)
        BundledInference.install(Path.of(args[0]),data)
        println("PASS actual bundled files installed into owned test workspace")
    }
}

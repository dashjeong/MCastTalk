package app.mcasttalk.windows.host

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

data class DataRootIdentity(
    val schemaVersion: Int,
    val instanceId: String,
)

object DataRootGate {
    fun requireInitialized(root: Path): DataRootIdentity {
        val marker = root.resolve("config").resolve("data-root.json")
        require(marker.isRegularFile()) {
            "MCastTalkData is not initialized: missing $marker"
        }
        val objectValue = parseFlatJsonObject(marker.readText(), maxChars = 16 * 1024)
        val schemaVersion = objectValue.requiredInt("schemaVersion")
        require(schemaVersion == 1) {
            "Unsupported MCastTalkData schema: $schemaVersion"
        }
        val instanceId = objectValue.optionalString("instanceId")
        require(!instanceId.isNullOrBlank()) {
            "MCastTalkData marker is missing instanceId"
        }
        return DataRootIdentity(
            schemaVersion = schemaVersion,
            instanceId = instanceId,
        )
    }
}

package com.buntap.app.routines

import com.buntap.app.domain.CoordinateValidator
import com.buntap.app.domain.Ids
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TargetSettings
import com.buntap.app.domain.model.VisualTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Shared JSON configuration. Polymorphic actions use a `"type"` field. */
object RoutineJson {
    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val pretty: Json = Json(json) { prettyPrint = true }

    fun encodeBody(body: RoutineBody): String = json.encodeToString(RoutineBody.serializer(), body)

    fun decodeBody(text: String): RoutineBody = json.decodeFromString(RoutineBody.serializer(), text)
}

/**
 * Version 1 of the BunTap routine exchange format:
 *
 * ```json
 * {
 *   "format": "buntap.routine",
 *   "schemaVersion": 1,
 *   "exportedAt": 1760000000000,
 *   "appVersion": "1.0.0",
 *   "routine": { "id": "...", "name": "...", "description": "...", "body": { "defaults": {...}, "actions": [ {"type": "wait", ...} ] } },
 *   "targets": [ { "id": "...", "name": "...", "width": 120, "height": 64, "settings": {...}, "pngBase64": "..." } ]
 * }
 * ```
 *
 * Files contain data only; nothing in them is ever executed as code.
 */
@Serializable
data class RoutineExportFile(
    val format: String = RoutineExchange.FORMAT_ID,
    val schemaVersion: Int = RoutineExchange.SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersion: String,
    val routine: ExportedRoutine,
    val targets: List<ExportedTarget> = emptyList(),
)

@Serializable
data class ExportedRoutine(
    val id: String,
    val name: String,
    val description: String = "",
    val body: RoutineBody,
)

@Serializable
data class ExportedTarget(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val settings: TargetSettings = TargetSettings(),
    @SerialName("pngBase64") val pngBase64: String,
)

/** A target that will be created by an import. */
data class ImportedTarget(
    val newId: String,
    val name: String,
    val width: Int,
    val height: Int,
    val settings: TargetSettings,
    val imageBytes: ByteArray,
)

/** A validated import, ready to be stored. IDs have already been regenerated. */
data class ImportPlan(
    val routineName: String,
    val description: String,
    val body: RoutineBody,
    val targets: List<ImportedTarget>,
    val warnings: List<String>,
)

sealed interface ImportResult {
    data class Ok(val plan: ImportPlan) : ImportResult
    data class Error(val message: String) : ImportResult
}

object RoutineExchange {
    const val FORMAT_ID = "buntap.routine"
    const val SCHEMA_VERSION = 1
    const val MAX_FILE_CHARS = 20_000_000
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    const val MAX_IMAGE_SIDE = 4096
    const val MAX_TARGETS = 50

    /** Builds the export JSON for [routine] including every target it references. */
    fun export(
        routine: Routine,
        targets: List<VisualTarget>,
        imageBytes: (VisualTarget) -> ByteArray?,
        appVersion: String,
        now: Long,
    ): String {
        val referenced = RoutineTree.all(routine.body.actions).mapNotNull { RoutineTree.targetIdOf(it) }.toSet()
        val exportedTargets = targets.filter { it.id in referenced }.mapNotNull { t ->
            val bytes = imageBytes(t) ?: return@mapNotNull null
            ExportedTarget(
                id = t.id,
                name = t.name,
                width = t.width,
                height = t.height,
                settings = TargetSettings(t.source, t.match, t.action),
                pngBase64 = Base64.getEncoder().encodeToString(bytes),
            )
        }
        val file = RoutineExportFile(
            exportedAt = now,
            appVersion = appVersion,
            routine = ExportedRoutine(routine.id, routine.name, routine.description, routine.body),
            targets = exportedTargets,
        )
        return RoutineJson.pretty.encodeToString(RoutineExportFile.serializer(), file)
    }

    /**
     * Parses and validates an exported routine.
     *
     * @param decodeImageSize returns (width, height) of encoded image bytes, or null if they are
     *   not a valid image (BitmapFactory on Android, a fake in unit tests).
     * @param existingTargetIds targets already in the library (references to them are kept).
     */
    fun parse(
        text: String,
        decodeImageSize: (ByteArray) -> Pair<Int, Int>?,
        existingTargetIds: Set<String>,
        limits: RateLimits,
        currentGeometry: ScreenGeometry?,
        newId: () -> String = Ids::newId,
    ): ImportResult {
        if (text.length > MAX_FILE_CHARS) return ImportResult.Error("The file is too large to be a BunTap routine.")
        val root: JsonObject = try {
            RoutineJson.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return ImportResult.Error("The file is not valid JSON.")
        }
        val format = (root["format"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (format != FORMAT_ID) return ImportResult.Error("This is not a BunTap routine file (missing \"format\": \"$FORMAT_ID\").")
        val version = try {
            root["schemaVersion"]?.jsonPrimitive?.intOrNull
        } catch (e: IllegalArgumentException) {
            null
        } ?: return ImportResult.Error("The file has no valid schemaVersion.")
        if (version > SCHEMA_VERSION) {
            return ImportResult.Error("This file uses format version $version, but this BunTap supports up to $SCHEMA_VERSION. Update BunTap to import it.")
        }
        if (version < 1) return ImportResult.Error("Unsupported format version $version.")

        val file = try {
            RoutineJson.json.decodeFromJsonElement(RoutineExportFile.serializer(), root)
        } catch (e: SerializationException) {
            return ImportResult.Error("The routine could not be read (unknown action type or damaged data): ${e.message?.take(200)}")
        } catch (e: IllegalArgumentException) {
            return ImportResult.Error("The routine contains invalid values: ${e.message?.take(200)}")
        }

        // --- Targets -------------------------------------------------------------------------
        if (file.targets.size > MAX_TARGETS) return ImportResult.Error("Too many targets in the file (max $MAX_TARGETS).")
        val duplicateTargetIds = file.targets.groupBy { it.id }.filter { it.value.size > 1 }.keys
        if (duplicateTargetIds.isNotEmpty()) return ImportResult.Error("The file contains duplicate target IDs.")
        val idMap = HashMap<String, String>()
        val importedTargets = ArrayList<ImportedTarget>()
        for (t in file.targets) {
            if (t.name.isBlank()) return ImportResult.Error("A target in the file has no name.")
            val bytes = try {
                Base64.getDecoder().decode(t.pngBase64)
            } catch (e: IllegalArgumentException) {
                return ImportResult.Error("The image for target “${t.name}” is not valid Base64.")
            }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return ImportResult.Error("The image for target “${t.name}” is empty or too large.")
            val size = decodeImageSize(bytes) ?: return ImportResult.Error("The image for target “${t.name}” is not a valid image.")
            if (size.first != t.width || size.second != t.height) {
                return ImportResult.Error("The image for target “${t.name}” does not match its declared size.")
            }
            if (t.width !in 1..MAX_IMAGE_SIDE || t.height !in 1..MAX_IMAGE_SIDE) {
                return ImportResult.Error("The image for target “${t.name}” has an unsupported size.")
            }
            val id = newId()
            idMap[t.id] = id
            importedTargets += ImportedTarget(id, t.name.take(RoutineValidator.MAX_NAME_LENGTH), t.width, t.height, t.settings, bytes)
        }

        // --- Actions: fresh IDs, remapped target references ----------------------------------
        val originalActions = file.routine.body.actions
        val missing = RoutineTree.all(originalActions).mapNotNull { RoutineTree.targetIdOf(it) }
            .filter { it !in idMap && it !in existingTargetIds }.toSet()
        if (missing.isNotEmpty()) {
            return ImportResult.Error("The routine refers to ${missing.size} target(s) that are not included in the file.")
        }
        val actions = originalActions.map { RoutineTree.remapTargets(RoutineTree.deepCopyWithNewIds(it, newId), idMap) }
        val body = file.routine.body.copy(actions = actions)

        val known = existingTargetIds + idMap.values
        // Coordinates are only range-checked here; off-screen positions become warnings below.
        val issues = RoutineValidator.validate(file.routine.name, body, known, limits, geometry = null)
        if (issues.isNotEmpty()) {
            return ImportResult.Error("The routine is not valid:\n" + issues.take(10).joinToString("\n") { "• ${it.message}" })
        }

        val warnings = ArrayList<String>()
        if (currentGeometry != null) {
            val offScreen = RoutineTree.all(actions).filterIsInstance<TapPointAction>()
                .count { CoordinateValidator.check(it.x, it.y, currentGeometry) != null }
            if (offScreen > 0) warnings += "$offScreen tap position(s) are outside this screen. Edit them before running."
            val ref = body.reference
            if (ref != null && !ref.sameSize(currentGeometry) && RoutineValidator.usesFixedCoordinates(body)) {
                warnings += "Fixed positions were chosen on a ${ref.describe()} screen; this screen is ${currentGeometry.describe()}. Check them before running."
            }
        }
        if (importedTargets.any { t -> t.settings.source?.let { currentGeometry != null && !it.sameSize(currentGeometry) } == true }) {
            warnings += "Some target images were captured on a different screen size and may not match until re-captured."
        }
        if (RoutineTree.all(actions).any { it is TypeTextAction }) {
            warnings += "This routine types text. Review the text before running it."
        }
        return ImportResult.Ok(
            ImportPlan(
                routineName = file.routine.name.take(RoutineValidator.MAX_NAME_LENGTH),
                description = file.routine.description.take(RoutineValidator.MAX_DESCRIPTION_LENGTH),
                body = body,
                targets = importedTargets,
                warnings = warnings,
            ),
        )
    }
}

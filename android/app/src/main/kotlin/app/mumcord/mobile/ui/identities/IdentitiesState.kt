// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import app.mumcord.mobile.bridge.Identity
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.SessionState

/** The longest name inside a certificate (Spec 03 FR-3.5). */
const val MAX_IDENTITY_NAME = 64

/** One card of the identity list. */
data class IdentityRow(
    val id: Long,
    val label: String,
    val isDefault: Boolean,
    /** Saved servers that connect with it: by name, or for the default one also by naming none. */
    val serverCount: Int,
    /** The start of the certificate hash, as on the card. */
    val fingerprintStart: String,
)

/** Everything about one identity, for its sheet. */
data class IdentityDetails(
    val id: Long,
    val label: String,
    val commonName: String,
    val fingerprint: String,
    val createdMs: Long,
    val isDefault: Boolean,
    val serverCount: Int,
    /** False for the only identity: the app always needs one. */
    val canDelete: Boolean,
    /** The identity that becomes the default when this default one is deleted. */
    val nextDefaultLabel: String?,
)

/** Why an identity file was not taken. */
enum class ImportProblem {
    /** The file could not be read or is far too large for an identity. */
    Unreadable,

    /** The password is wrong or the file holds no identity; the engine cannot tell them apart yet. */
    WrongPasswordOrFile,
    Duplicate,
    Failed,
}

/** What is open over the identity screens, as the user left it. Passwords stay in memory only. */
sealed interface IdentitySheetLocal {
    data class Details(val identityId: Long) : IdentitySheetLocal

    data class Create(
        val name: String = "",
        val saving: Boolean = false,
        val error: ConnectionError? = null,
    ) : IdentitySheetLocal

    data class Import(
        val password: String = "",
        val saving: Boolean = false,
        val problem: ImportProblem? = null,
    ) : IdentitySheetLocal

    /** [fromDelete]: opened by "Back up first", so closing it returns to the delete warning. */
    data class Export(
        val identityId: Long,
        val password: String = "",
        val repeat: String = "",
        val saving: Boolean = false,
        val failed: Boolean = false,
        val fromDelete: Boolean = false,
    ) : IdentitySheetLocal {
        val mismatch: Boolean
            get() = repeat.isNotEmpty() && password != repeat

        /** A backup without a password is not allowed (Spec 03 FR-3.8). */
        val canExport: Boolean
            get() = password.isNotEmpty() && password == repeat && !saving
    }

    data class Delete(
        val identityId: Long,
        val typed: String = "",
        val deleting: Boolean = false,
        val error: ConnectionError? = null,
    ) : IdentitySheetLocal
}

/** The sheet with what it shows about its identity. */
sealed interface IdentitySheet {
    data class Details(val identity: IdentityDetails) : IdentitySheet

    data class Create(val form: IdentitySheetLocal.Create) : IdentitySheet {
        val canCreate: Boolean
            get() = form.name.isNotBlank() && !form.saving
    }

    data class Import(val form: IdentitySheetLocal.Import) : IdentitySheet

    data class Export(val identity: IdentityDetails, val form: IdentitySheetLocal.Export) : IdentitySheet

    /** [defaultLabel]: the default identity, which the servers of a deleted other one fall back to. */
    data class Delete(
        val identity: IdentityDetails,
        val form: IdentitySheetLocal.Delete,
        val defaultLabel: String,
    ) : IdentitySheet {
        /** The label has to be typed exactly (Spec 03 FR-3.9). */
        val canDelete: Boolean
            get() = identity.canDelete && form.typed.trim() == identity.label && !form.deleting
    }
}

/** A backup that is ready for the system's share sheet. Not a data class: bytes have no equality. */
class ExportedIdentity(val fileName: String, val bytes: ByteArray)

/** The identity screens' own state. */
data class IdentitiesLocal(
    /** The identity list is open (from the More tab). */
    val open: Boolean = false,
    val sheet: IdentitySheetLocal? = null,
    /** A picked file that was not even read; shown on the screen, not in a sheet. */
    val unreadableFile: Boolean = false,
    val exported: ExportedIdentity? = null,
    /** The open "Scan from desktop app" screen. */
    val scan: ScanState? = null,
)

data class IdentitiesUiState(
    val open: Boolean = false,
    /** False until the identities were read. */
    val known: Boolean = false,
    val rows: List<IdentityRow> = emptyList(),
    val sheet: IdentitySheet? = null,
    val unreadableFile: Boolean = false,
    val exported: ExportedIdentity? = null,
    val scan: ScanState? = null,
)

fun identitiesUiState(session: SessionState, local: IdentitiesLocal): IdentitiesUiState {
    val identities = session.identities.orEmpty()
    fun details(id: Long): IdentityDetails? =
        identities.firstOrNull { it.id == id }?.let { identityDetails(it, identities, session.servers) }
    val sheet = when (val open = local.sheet) {
        null -> null
        is IdentitySheetLocal.Create -> IdentitySheet.Create(open)
        is IdentitySheetLocal.Import -> IdentitySheet.Import(open)
        // A sheet about an identity that is gone (deleted, also by a transfer) closes.
        is IdentitySheetLocal.Details -> details(open.identityId)?.let { IdentitySheet.Details(it) }
        is IdentitySheetLocal.Export -> details(open.identityId)?.let { IdentitySheet.Export(it, open) }
        is IdentitySheetLocal.Delete -> details(open.identityId)?.let { details ->
            IdentitySheet.Delete(details, open, identities.firstOrNull { it.isDefault }?.label.orEmpty())
        }
    }
    return IdentitiesUiState(
        open = local.open,
        known = session.identities != null,
        rows = identities.map { identity ->
            IdentityRow(
                id = identity.id,
                label = identity.label,
                isDefault = identity.isDefault,
                serverCount = serverCount(identity, session.servers),
                fingerprintStart = fingerprintStart(identity.fingerprint),
            )
        },
        sheet = sheet,
        unreadableFile = local.unreadableFile,
        exported = local.exported,
        scan = local.scan,
    )
}

private fun identityDetails(identity: Identity, all: List<Identity>, servers: List<SavedServer>) = IdentityDetails(
    id = identity.id,
    label = identity.label,
    commonName = identity.commonName,
    fingerprint = fingerprintGroups(identity.fingerprint),
    createdMs = identity.createdMs,
    isDefault = identity.isDefault,
    serverCount = serverCount(identity, servers),
    canDelete = all.size > 1,
    nextDefaultLabel = if (identity.isDefault) nextDefault(identity, all)?.label else null,
)

/** A server that names no identity connects with the default one. */
private fun serverCount(identity: Identity, servers: List<SavedServer>): Int =
    servers.count { it.identityId == identity.id || (identity.isDefault && it.identityId == null) }

/** The oldest remaining identity becomes the default (Spec 03 FR-3.9). */
private fun nextDefault(deleted: Identity, all: List<Identity>): Identity? =
    all.filter { it.id != deleted.id }.minWithOrNull(compareBy<Identity> { it.createdMs }.thenBy { it.id })

private const val START_BYTES = 8

/** The first bytes of the hash as on the card: `4F:A9:1C:…`. */
fun fingerprintStart(fingerprint: String): String =
    fingerprint.uppercase().chunked(2).take(START_BYTES).joinToString(":")

/** The whole hash in blocks of four, as the desktop app shows it. */
fun fingerprintGroups(fingerprint: String): String = fingerprint.uppercase().chunked(4).joinToString(" ")

/** `mumcord-identity-‹label›.p12`, with only characters every file system takes. */
fun exportFileName(label: String): String {
    val safe = label.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
        .joinToString("")
        .trim('-')
        .take(MAX_FILE_LABEL)
    return if (safe.isEmpty()) "mumcord-identity.p12" else "mumcord-identity-$safe.p12"
}

private const val MAX_FILE_LABEL = 40

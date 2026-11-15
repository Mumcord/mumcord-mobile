// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import app.mumcord.mobile.bridge.Identity
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.savedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentitiesStateTest {
    private fun identity(id: Long, label: String, default: Boolean = false, createdMs: Long = id) = Identity(
        id = id,
        label = label,
        commonName = "$label on servers",
        fingerprint = "4fa91c7ed208b36a".padEnd(40, id.toString().last()),
        createdMs = createdMs,
        isDefault = default,
        serverIds = emptyList(),
    )

    private val lea = identity(1, "Lea", default = true)
    private val work = identity(2, "Work")
    private val old = identity(3, "Old", createdMs = 0)

    private fun state(
        identities: List<Identity>? = listOf(lea, work),
        local: IdentitiesLocal = IdentitiesLocal(),
    ) = identitiesUiState(
        SessionState(
            identities = identities,
            servers = listOf(
                savedServer(1, "Nordlicht"),
                savedServer(2, "Tidepool"),
                savedServer(3, "Lantern Guild").copy(identityId = 2),
                savedServer(4, "Quiet Harbor").copy(identityId = 1),
            ),
        ),
        local,
    )

    @Test
    fun theIdentitiesAreNotKnownBeforeTheyWereRead() {
        assertFalse(state(identities = null).known)
        assertTrue(state(identities = emptyList()).known)
    }

    @Test
    fun aRowCountsTheServersThatConnectWithIt() {
        val rows = state().rows
        // The default one is also used by the servers that name no identity.
        assertEquals(listOf(3, 1), rows.map { it.serverCount })
        assertEquals(listOf(true, false), rows.map { it.isDefault })
        assertEquals(listOf("Lea", "Work"), rows.map { it.label })
    }

    @Test
    fun theFingerprintIsShownAsItsStartOnTheCardAndInBlocksInTheSheet() {
        assertEquals("4F:A9:1C:7E:D2:08:B3:6A", state().rows.first().fingerprintStart)
        val details = state(local = IdentitiesLocal(sheet = IdentitySheetLocal.Details(1))).sheet as IdentitySheet.Details
        assertEquals("4FA9 1C7E D208 B36A 1111 1111 1111 1111 1111 1111", details.identity.fingerprint)
        assertEquals("Lea on servers", details.identity.commonName)
    }

    @Test
    fun theLastIdentityCannotBeDeleted() {
        val only = state(identities = listOf(lea), local = IdentitiesLocal(sheet = IdentitySheetLocal.Delete(1, typed = "Lea")))
        val sheet = only.sheet as IdentitySheet.Delete
        assertFalse(sheet.identity.canDelete)
        assertFalse(sheet.canDelete)
    }

    @Test
    fun deletingNeedsTheLabelTypedExactly() {
        fun sheet(typed: String, deleting: Boolean = false) =
            state(local = IdentitiesLocal(sheet = IdentitySheetLocal.Delete(2, typed, deleting))).sheet as IdentitySheet.Delete
        assertFalse(sheet("").canDelete)
        assertFalse(sheet("work").canDelete)
        assertTrue(sheet("Work").canDelete)
        assertTrue(sheet(" Work ").canDelete)
        assertFalse(sheet("Work", deleting = true).canDelete)
        // The servers of a deleted identity fall back to the default one.
        assertEquals("Lea", sheet("Work").defaultLabel)
        assertNull(sheet("Work").identity.nextDefaultLabel)
    }

    @Test
    fun deletingTheDefaultNamesTheOldestRemainingIdentityAsTheNextDefault() {
        val sheet = state(
            identities = listOf(lea, work, old),
            local = IdentitiesLocal(sheet = IdentitySheetLocal.Delete(1)),
        ).sheet as IdentitySheet.Delete
        assertEquals("Old", sheet.identity.nextDefaultLabel)
    }

    @Test
    fun aSheetAboutAnIdentityThatIsGoneCloses() {
        assertNull(state(local = IdentitiesLocal(sheet = IdentitySheetLocal.Details(9))).sheet)
        assertNull(state(local = IdentitiesLocal(sheet = IdentitySheetLocal.Export(9))).sheet)
        assertNull(state(local = IdentitiesLocal(sheet = IdentitySheetLocal.Delete(9))).sheet)
    }

    @Test
    fun aBackupNeedsTheSamePasswordTwiceAndNotAnEmptyOne() {
        assertFalse(IdentitySheetLocal.Export(1).canExport)
        assertFalse(IdentitySheetLocal.Export(1, password = "a", repeat = "b").canExport)
        assertTrue(IdentitySheetLocal.Export(1, password = "a", repeat = "b").mismatch)
        // Nothing is called a mismatch before the second field is typed into.
        assertFalse(IdentitySheetLocal.Export(1, password = "a").mismatch)
        assertTrue(IdentitySheetLocal.Export(1, password = "a", repeat = "a").canExport)
        assertFalse(IdentitySheetLocal.Export(1, password = "a", repeat = "a", saving = true).canExport)
    }

    @Test
    fun aNewIdentityNeedsAName() {
        assertFalse(IdentitySheet.Create(IdentitySheetLocal.Create()).canCreate)
        assertFalse(IdentitySheet.Create(IdentitySheetLocal.Create(name = "  ")).canCreate)
        assertTrue(IdentitySheet.Create(IdentitySheetLocal.Create(name = "Lea")).canCreate)
        assertFalse(IdentitySheet.Create(IdentitySheetLocal.Create(name = "Lea", saving = true)).canCreate)
    }

    @Test
    fun theBackupFileIsNamedAfterTheLabelWithSafeCharactersOnly() {
        assertEquals("mumcord-identity-Lea.p12", exportFileName("Lea"))
        assertEquals("mumcord-identity-my-main-id.p12", exportFileName("my/main id"))
        assertEquals("mumcord-identity.p12", exportFileName("../.."))
        assertEquals("mumcord-identity-${"x".repeat(40)}.p12", exportFileName("x".repeat(64)))
    }
}

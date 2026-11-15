// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.app.ApplicationExitInfo
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallInfoTest {
    private val lobby = CallInfo("Nordlicht", "Lobby", muted = false, deafened = false)

    @Test
    fun theCallIsNamedAfterServerAndChannel() {
        assertEquals("Nordlicht · Lobby", lobby.title)
        assertEquals("Nordlicht", lobby.copy(channelName = null).title)
    }

    @Test
    fun theServiceStartsWithTheSessionFollowsItAndStopsWithIt() {
        assertEquals(ServiceStep.None, serviceStep(running = false, previous = null, next = null))
        assertEquals(ServiceStep.Start, serviceStep(running = false, previous = null, next = lobby))
        assertEquals(ServiceStep.None, serviceStep(running = true, previous = lobby, next = lobby))
        assertEquals(
            ServiceStep.Update,
            serviceStep(running = true, previous = lobby, next = lobby.copy(channelName = "Raid")),
        )
        assertEquals(ServiceStep.Update, serviceStep(running = true, previous = lobby, next = lobby.copy(muted = true)))
        assertEquals(ServiceStep.Stop, serviceStep(running = true, previous = lobby, next = null))
    }

    @Test
    fun aServiceThatWasRefusedIsTriedAgainAndStillEndsWithTheSession() {
        assertEquals(ServiceStep.Start, serviceStep(running = false, previous = lobby, next = lobby.copy(muted = true)))
        assertEquals(ServiceStep.Stop, serviceStep(running = false, previous = lobby, next = null))
    }

    @Test
    fun theMicrophoneTypeIsOnlyUsedWithTheMicrophonePermission() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL, foregroundServiceTypes(microphoneGranted = false))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundServiceTypes(microphoneGranted = true),
        )
    }

    @Test
    fun theNotificationsButtonsFollowMuteAndDeafen() {
        val talking = callNotificationModel(lobby)
        assertEquals(CallNotificationModel.Status.Connected, talking.status)
        assertEquals(CallNotificationModel.MuteAction.Mute, talking.muteAction)
        assertEquals(CallNotificationModel.DeafenAction.Deafen, talking.deafenAction)

        val muted = callNotificationModel(lobby.copy(muted = true))
        assertEquals(CallNotificationModel.Status.Muted, muted.status)
        assertEquals(CallNotificationModel.MuteAction.Unmute, muted.muteAction)
        assertEquals(CallNotificationModel.DeafenAction.Deafen, muted.deafenAction)

        val deafened = callNotificationModel(lobby.copy(muted = true, deafened = true))
        assertEquals(CallNotificationModel.Status.Deafened, deafened.status)
        assertEquals(CallNotificationModel.MuteAction.Unmute, deafened.muteAction)
        assertEquals(CallNotificationModel.DeafenAction.Undeafen, deafened.deafenAction)
    }

    @Test
    fun aSessionCutOffByTheSystemIsReportedAtTheNextStart() {
        val marker = SessionMarker(serverId = 7, serverName = "Nordlicht")
        val notice = SessionEndedNotice(serverId = 7, serverName = "Nordlicht")
        assertEquals(notice, sessionEndedNotice(marker, ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(notice, sessionEndedNotice(marker, ApplicationExitInfo.REASON_OTHER))
        // The system has no record of why the last run ended (after a restart of the phone).
        assertEquals(notice, sessionEndedNotice(marker, exitReason = null))
    }

    @Test
    fun noNoticeWithoutACutOffSessionOrWhenTheUserClosedTheApp() {
        val marker = SessionMarker(serverId = 7, serverName = "Nordlicht")
        assertNull(sessionEndedNotice(marker = null, exitReason = ApplicationExitInfo.REASON_LOW_MEMORY))
        assertNull(sessionEndedNotice(marker, ApplicationExitInfo.REASON_USER_REQUESTED))
        assertNull(sessionEndedNotice(marker, ApplicationExitInfo.REASON_USER_STOPPED))
    }
}

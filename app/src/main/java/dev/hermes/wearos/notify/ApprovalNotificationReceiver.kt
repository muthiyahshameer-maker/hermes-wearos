package dev.hermes.wearos.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.hermes.wearos.WearApp

/**
 * Backs the Approve/Deny buttons on the approval notification (and is directly adb-testable):
 *   adb shell am broadcast -n dev.hermes.wearos/.notify.ApprovalNotificationReceiver \
 *        --es action dev.hermes.wearos.APPROVE --es run <runId>
 *
 * Works even after process death: instantiating the receiver runs Application.onCreate, which
 * boots WearApp.Graph and resumes the active chat's in-flight run; respondApprovalFromNotification
 * waits briefly for the approval to surface, else reads it from the run-status snapshot.
 */
class ApprovalNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(Notifs.EXTRA_RUN).orEmpty()
        val action = intent.getStringExtra(Notifs.EXTRA_ACTION) ?: intent.action ?: ""
        if (runId.isEmpty()) return
        val approve = action == Notifs.ACTION_APPROVE
        Log.i(TAG, "approval action $action run=$runId")
        Notifs.cancelApproval(context, runId)
        val pending = goAsync()
        WearApp.Graph.session.respondApprovalFromNotification(runId, approve) { pending.finish() }
    }

    private companion object { const val TAG = "HermesWear" }
}

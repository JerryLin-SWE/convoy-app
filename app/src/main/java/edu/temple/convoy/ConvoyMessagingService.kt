package edu.temple.convoy

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

class ConvoyMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // Called when FCM gives your device a token
    // We must report this token to the app server
    override fun onNewToken(token: String) {
        super.onNewToken(token)

        val store = SessionStore(this)

        serviceScope.launch {
            val username = store.username.first() ?: return@launch
            val sessionKey = store.sessionKey.first() ?: return@launch

            try {
                ApiClient.api.account(
                    action = "UPDATE",
                    username = username,
                    password = null,
                    fcmToken = token,
                    firstname = null,
                    lastname = null,
                    sessionKey = sessionKey
                )
            } catch (e: Exception) {
                // Silently ignore
            }
        }
    }

    // Called when an FCM message arrives
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val payload = message.data["payload"] ?: return

        try {
            val json = JSONObject(payload)
            val action = json.getString("action")

            when (action) {
                "UPDATE" -> {
                    // Forward to MainActivity via broadcast
                    val intent = Intent("edu.temple.convoy.ACTION_CONVOY_UPDATE")
                    intent.putExtra("payload", payload)
                    sendBroadcast(intent)
                }
                "END" -> {
                    // Forward to MainActivity via broadcast
                    val intent = Intent("edu.temple.convoy.ACTION_CONVOY_END")
                    intent.putExtra("convoy_id", json.getString("convoy_id"))
                    sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            // Bad JSON, ignore
        }
    }
}
package edu.temple.convoy
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.view.View
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.TextView
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.CameraUpdateFactory
import kotlinx.coroutines.launch
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.runBlocking
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File


class MainActivity : AppCompatActivity() {


    private lateinit var store: SessionStore
    private var gMap: GoogleMap? = null
    private var userMarker: Marker? = null
    private val LOCATION_REQ = 1001
    private val AUDIO_REQ = 1002
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var activeConvoyId: String? = null
    private val memberMarkers = mutableMapOf<String, Marker>()

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    private fun setConvoyUI(convoyId: String?, tv: TextView, btnStart: Button, btnEnd: Button) {
        val btnVoice = findViewById<Button>(R.id.btnVoice)
        if (!convoyId.isNullOrBlank()) {
            activeConvoyId = convoyId
            tv.text = "Convoy: $convoyId"
            btnStart.visibility = View.GONE
            btnEnd.visibility = View.VISIBLE
            btnVoice?.visibility = View.VISIBLE
        } else {
            activeConvoyId = null
            tv.text = "No convoy"
            btnEnd.visibility = View.GONE
            btnStart.visibility = View.VISIBLE
            btnVoice?.visibility = View.GONE
        }
    }

    private fun startConvoyService() {
        val svc = Intent(this@MainActivity, ConvoyLocationService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc)
        else startService(svc)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = SessionStore(this)

        val messageFilter = IntentFilter("edu.temple.convoy.ACTION_CONVOY_MESSAGE")
        ContextCompat.registerReceiver(this, messageReceiver, messageFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            val key = store.sessionKey.first()
            if (key == null) {
                showAuthChoice()
            } else {
                showMainPlaceholder()
            }
        }
    }

    private fun restartToAuth() {
        stopService(Intent(this, ConvoyLocationService::class.java))
        lifecycleScope.launch {
            val username = store.username.first()
            val sessionKey = store.sessionKey.first()
            if (username != null && sessionKey != null) {
                try {
                    ApiClient.api.account(
                        action = "LOGOUT",
                        username = username,
                        password = null,
                        fcmToken = null,
                        firstname = null,
                        lastname = null,
                        sessionKey = sessionKey
                    )
                } catch (e: Exception) { }
            }
            store.clearAll()
            val i = Intent(this@MainActivity, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            finish()
        }
    }


    private fun showAuthChoice(){
        setContentView(R.layout.view_auth_choice)

        findViewById<Button>(R.id.btnCreate).setOnClickListener {
            //go to the register screen
            showRegisterDialog()
        }

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            //go to Login screen
            showLoginDialog()
        }
    }

    private fun showMainPlaceholder(){
        setContentView(R.layout.activity_main)

        // Re-register FCM token on every app start in case it wasn't sent before
        lifecycleScope.launch {
            val username = store.username.first() ?: return@launch
            val sessionKey = store.sessionKey.first() ?: return@launch
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    lifecycleScope.launch {
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
                        } catch (e: Exception) { }
                    }
                }
            }
        }

        //this become the map screen
        val tvConvoyId = findViewById<android.widget.TextView>(R.id.tvConvoyId)
        val btnStart = findViewById<Button>(R.id.btnStartConvoy)
        val btnJoin = findViewById<Button>(R.id.btnJoinConvoy)
        val btnLeave = findViewById<Button>(R.id.btnLeaveConvoy)
        val btnEnd = findViewById<Button>(R.id.btnEndConvoy)

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            restartToAuth()
        }

        btnStart.setOnClickListener {
            lifecycleScope.launch {
                val username = store.username.first()
                val sessionKey = store.sessionKey.first()

                if (username == null || sessionKey == null) {
                    android.widget.Toast.makeText(this@MainActivity, "Not logged in", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }

                try {
                    val resp = ApiClient.api.convoy(
                        action = "CREATE",
                        username = username,
                        sessionKey = sessionKey,
                        convoyId = null
                    )

                    val status = resp["status"]?.toString()
                    if (status == "SUCCESS") {
                        val convoyId = resp["convoy_id"]?.toString()
                        store.saveConvoyId(convoyId)
                        runOnUiThread { setConvoyUI(convoyId, tvConvoyId, btnStart, btnEnd) }

                        val svc = Intent(this@MainActivity, ConvoyLocationService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            startForegroundService(svc)
                        } else {
                            startService(svc)
                        }


                        android.widget.Toast.makeText(this@MainActivity, "Convoy started", android.widget.Toast.LENGTH_SHORT).show()
                        // later: start foreground service here
                    } else {
                        android.widget.Toast.makeText(this@MainActivity, resp["message"]?.toString() ?: "Error", android.widget.Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "Network error", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        btnEnd.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("End Convoy?")
                .setMessage("Are you sure you want to end this convoy?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("End") { _, _ ->
                    lifecycleScope.launch {
                        val username = store.username.first() ?: return@launch
                        val sessionKey = store.sessionKey.first() ?: return@launch
                        val convoyId = activeConvoyId ?: run {
                            android.widget.Toast.makeText(this@MainActivity, "No active convoy to leave", android.widget.Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        try {
                            val resp = ApiClient.api.convoy(
                                action = "END",
                                username = username,
                                sessionKey = sessionKey,
                                convoyId = convoyId
                            )
                            if (resp["status"]?.toString() == "SUCCESS") {
                                store.saveConvoyId(null)
                                stopService(Intent(this@MainActivity, ConvoyLocationService::class.java))
                                setConvoyUI(null, tvConvoyId, btnStart, btnEnd)
                                android.widget.Toast.makeText(this@MainActivity, "Convoy ended", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(this@MainActivity, "Error ending convoy", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }.show()
        }
        btnJoin.setOnClickListener {
            val input = android.widget.EditText(this)
            input.hint = "Enter convoy id"
            AlertDialog.Builder(this)
                .setTitle("Join Convoy")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Join") { _, _ ->
                    val id = input.text.toString().trim()
                    if (id.isEmpty()) {
                        android.widget.Toast.makeText(this, "Enter a convoy id", android.widget.Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    lifecycleScope.launch {
                        val username = store.username.first() ?: return@launch
                        val sessionKey = store.sessionKey.first() ?: return@launch
                        try {
                            val resp = ApiClient.api.convoy(
                                action = "JOIN",
                                username = username,
                                sessionKey = sessionKey,
                                convoyId = id
                            )
                            if (resp["status"]?.toString() == "SUCCESS") {
                                store.saveConvoyId(id)
                                setConvoyUI(id, tvConvoyId, btnStart, btnEnd)
                                startConvoyService()
                            } else {
                                android.widget.Toast.makeText(this@MainActivity,
                                    resp["message"]?.toString() ?: "Error joining",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(this@MainActivity, "Network error", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }.show()
        }

        btnLeave.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Leave convoy?")
                .setMessage("Stop sharing location and leave this convoy?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK") { _, _ ->
                    lifecycleScope.launch {
                        val username = store.username.first() ?: return@launch
                        val sessionKey = store.sessionKey.first() ?: return@launch
                        val convoyId = activeConvoyId ?: run {
                            android.widget.Toast.makeText(this@MainActivity, "No active convoy to leave", android.widget.Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        try {
                            ApiClient.api.convoy(
                                action = "LEAVE",
                                username = username,
                                sessionKey = sessionKey,
                                convoyId = convoyId
                            )
                        } catch (e: Exception) { }
                        store.saveConvoyId(null)
                        stopService(Intent(this@MainActivity, ConvoyLocationService::class.java))
                        setConvoyUI(null, tvConvoyId, btnStart, btnEnd)
                    }
                }.show()
        }

        val btnVoice = findViewById<Button>(R.id.btnVoice)
        btnVoice.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQ)
                return@setOnClickListener
            }
            if (!isRecording) {
                startRecording()
                btnVoice.text = "⏹"
                android.widget.Toast.makeText(this, "Recording…", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                stopRecordingAndSend(btnVoice)
            }
        }

        val mapFrag = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFrag.getMapAsync { map ->
            gMap = map
            ensureLocationPermissionAndStart()
        }

        lifecycleScope.launch {
            val username = store.username.first()
            val sessionKey = store.sessionKey.first()

            if (username != null && sessionKey != null) {
                try {
                    val resp = ApiClient.api.convoy(
                        action = "QUERY",
                        username = username,
                        sessionKey = sessionKey,
                        convoyId = null
                    )

                    val rawStatus = resp["status"]?.toString()
                    val rawConvoyId = resp["convoy_id"]?.toString()
                    val rawActive = resp["active"]
                    runOnUiThread {
                        android.widget.Toast.makeText(this@MainActivity,
                            "QUERY: status=$rawStatus active=$rawActive(${rawActive?.javaClass?.simpleName}) convoy=$rawConvoyId",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    if (rawStatus == "SUCCESS") {
                        val convoyId = rawConvoyId?.takeIf { it != "null" && it.isNotBlank() }
                        val isActive = when (val v = rawActive) {
                            is Boolean -> v
                            is Double -> v != 0.0
                            is Int -> v != 0
                            is Long -> v != 0L
                            is String -> v == "1" || v.toBoolean()
                            else -> false
                        }

                        if (convoyId != null) {
                            store.saveConvoyId(convoyId)
                            runOnUiThread {
                                setConvoyUI(convoyId, tvConvoyId, btnStart, btnEnd)
                                startConvoyService()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore - no active convoy
                }
            }


    }




    }
    private fun ensureLocationPermissionAndStart() {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_REQ
            )
            return
        }

        startOneShotLocation()
    }



    private fun startOneShotLocation() {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            return
        }

        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener

                val pos = LatLng(loc.latitude, loc.longitude)

                if (userMarker == null) {
                    userMarker = gMap?.addMarker(MarkerOptions().position(pos).title("You"))
                    gMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
                } else {
                    userMarker?.position = pos
                }
            }
            .addOnFailureListener {
                //  Toast/log
            }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_REQ && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startOneShotLocation()
        }
        if (requestCode == AUDIO_REQ && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            android.widget.Toast.makeText(this, "Mic permission granted — tap 🎤 again to record", android.widget.Toast.LENGTH_SHORT).show()
        }
    }





    private fun startRecording() {
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.mp4")
        audioFile = file
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        isRecording = true
    }

    private fun stopRecordingAndSend(btnVoice: Button) {
        mediaRecorder?.apply { stop(); release() }
        mediaRecorder = null
        isRecording = false
        btnVoice.text = "🎤"

        val file = audioFile ?: return
        lifecycleScope.launch {
            val username = store.username.first() ?: return@launch
            val sessionKey = store.sessionKey.first() ?: return@launch
            val convoyId = activeConvoyId ?: return@launch
            try {
                val filePart = MultipartBody.Part.createFormData(
                    "message_file", file.name,
                    file.asRequestBody("audio/mp4".toMediaTypeOrNull())
                )
                val resp = ApiClient.api.message(
                    action = "MESSAGE".toRequestBody("text/plain".toMediaTypeOrNull()),
                    username = username.toRequestBody("text/plain".toMediaTypeOrNull()),
                    sessionKey = sessionKey.toRequestBody("text/plain".toMediaTypeOrNull()),
                    convoyId = convoyId.toRequestBody("text/plain".toMediaTypeOrNull()),
                    messageFile = filePart
                )
                if (resp["status"]?.toString() != "SUCCESS") {
                    runOnUiThread {
                        android.widget.Toast.makeText(this@MainActivity,
                            resp["message"]?.toString() ?: "Send failed",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity,
                        "Error sending voice message", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                file.delete()
                audioFile = null
            }
        }
    }

    private fun showRegisterDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_register, null)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Create Account")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val first = view.findViewById<android.widget.EditText>(R.id.etFirst).text.toString().trim()
                val last = view.findViewById<android.widget.EditText>(R.id.etLast).text.toString().trim()
                val user = view.findViewById<android.widget.EditText>(R.id.etUsername).text.toString().trim()
                val pass = view.findViewById<android.widget.EditText>(R.id.etPassword).text.toString().trim()

                if (first.isEmpty() || last.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    android.widget.Toast.makeText(this@MainActivity, "Fill in all fields", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                //  call REGISTER API here
                lifecycleScope.launch {
                    try {
                        val response = ApiClient.api.account(
                            action = "REGISTER",
                            username = user,
                            password = pass,
                            firstname = first,
                            lastname = last,
                            fcmToken = null,
                            sessionKey = null

                        )
                        val status = response["status"]?.toString()
                        if (status == "SUCCESS") {
                            val sessionKey = response["session_key"].toString()

                            store.saveLogin(user, first, last, sessionKey)

                            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    val token = task.result
                                    lifecycleScope.launch {
                                        try {
                                            ApiClient.api.account(
                                                action = "UPDATE",
                                                username = user,
                                                password = null,
                                                fcmToken = token,
                                                firstname = null,
                                                lastname = null,
                                                sessionKey = sessionKey
                                            )
                                        } catch (e: Exception) { }
                                    }
                                }
                            }

                            runOnUiThread {
                                android.widget.Toast.makeText(this@MainActivity, "Account created!", android.widget.Toast.LENGTH_SHORT).show()

                                dialog.dismiss()
                                showMainPlaceholder()
                            }
                        }else {
                            val message = response["message"].toString()
                            android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                        }

                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this@MainActivity, e.message ?: "Network error", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showLoginDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_login, null)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Log In")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Login", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {

                val user = view.findViewById<android.widget.EditText>(R.id.etUsername).text.toString().trim()
                val pass = view.findViewById<android.widget.EditText>(R.id.etPassword).text.toString().trim()

                if (user.isEmpty() || pass.isEmpty()) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Fill in all fields",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch {
                    try {
                        val response = ApiClient.api.account(
                            action = "LOGIN",
                            username = user,
                            password = pass,
                            firstname = null,
                            fcmToken = null,
                            lastname = null,
                            sessionKey = null
                        )

                        val status = response["status"]?.toString()

                        if (status == "SUCCESS") {

                            val sessionKey = response["session_key"].toString()

                            store.saveLogin(user, null, null, sessionKey)

                            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                                android.util.Log.d("ConvoyFCM", "token task success=${task.isSuccessful} token=${task.result}")
                                if (task.isSuccessful) {
                                    val token = task.result
                                    lifecycleScope.launch {
                                        try {
                                            val updateResp = ApiClient.api.account(
                                                action = "UPDATE",
                                                username = user,
                                                password = null,
                                                fcmToken = token,
                                                firstname = null,
                                                lastname = null,
                                                sessionKey = sessionKey
                                            )
                                            android.util.Log.d("ConvoyFCM", "FCM UPDATE resp=$updateResp")
                                        } catch (e: Exception) {
                                            android.util.Log.e("ConvoyFCM", "FCM UPDATE error", e)
                                        }
                                    }
                                }
                            }

                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "Login successful!",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()

                                dialog.dismiss()
                                showMainPlaceholder()
                            }

                        }else {
                            val message = response["message"]?.toString()
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                message,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }

                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Network error",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        dialog.show()
    }
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "edu.temple.convoy.ACTION_CONVOY_MESSAGE") return
            val sender = intent.getStringExtra("username") ?: "Someone"
            val url = intent.getStringExtra("message_url") ?: return
            android.widget.Toast.makeText(this@MainActivity, "🎤 $sender sent a voice message", android.widget.Toast.LENGTH_SHORT).show()
            val player = MediaPlayer()
            player.setDataSource(url)
            player.prepareAsync()
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { it.release() }
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ConvoyLocationService.ACTION_LOCATION) return

            val lat = intent.getDoubleExtra(ConvoyLocationService.EXTRA_LAT, 0.0)
            val lng = intent.getDoubleExtra(ConvoyLocationService.EXTRA_LNG, 0.0)
            val pos = LatLng(lat, lng)

            if (userMarker == null) {
                userMarker = gMap?.addMarker(MarkerOptions().position(pos).title("You"))
                gMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
            } else {
                userMarker?.position = pos
            }
        }
    }
    private val convoyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "edu.temple.convoy.ACTION_CONVOY_UPDATE" -> {
                    val payload = intent.getStringExtra("payload") ?: return
                    val json = org.json.JSONObject(payload)
                    val data = json.getJSONArray("data")
                    val currentUsername = runBlocking { store.username.first() }

                    // Remove markers for users no longer in convoy
                    val activeUsernames = (0 until data.length()).map {
                        data.getJSONObject(it).getString("username")
                    }.toSet()
                    val toRemove = memberMarkers.keys.filter { it !in activeUsernames }
                    toRemove.forEach { memberMarkers.remove(it)?.remove() }

                    // Add/update markers for each member except yourself
                    for (i in 0 until data.length()) {
                        val user = data.getJSONObject(i)
                        val username = user.getString("username")
                        if (username == currentUsername) continue // skip yourself

                        val lat = user.getDouble("latitude")
                        val lng = user.getDouble("longitude")
                        val name = "${user.getString("firstname")} ${user.getString("lastname")}"
                        val pos = LatLng(lat, lng)

                        val existing = memberMarkers[username]
                        if (existing == null) {
                            val marker = gMap?.addMarker(
                                MarkerOptions()
                                    .position(pos)
                                    .title(name)
                                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory
                                        .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_BLUE))
                            )
                            if (marker != null) memberMarkers[username] = marker
                        } else {
                            existing.position = pos
                        }
                    }

                    // Zoom to show all members
                    if (memberMarkers.isNotEmpty()) {
                        val builder = com.google.android.gms.maps.model.LatLngBounds.Builder()
                        memberMarkers.values.forEach { builder.include(it.position) }
                        userMarker?.let { builder.include(it.position) }
                        try {
                            gMap?.animateCamera(
                                CameraUpdateFactory.newLatLngBounds(builder.build(), 200)
                            )
                        } catch (e: Exception) { }
                    }
                }

                "edu.temple.convoy.ACTION_CONVOY_END" -> {
                    // Clear all member markers
                    memberMarkers.values.forEach { it.remove() }
                    memberMarkers.clear()
                    // Find UI views and reset
                    val tv = findViewById<TextView>(R.id.tvConvoyId)
                    val btnStart = findViewById<Button>(R.id.btnStartConvoy)
                    val btnEnd = findViewById<Button>(R.id.btnEndConvoy)
                    store.let {
                        lifecycleScope.launch { it.saveConvoyId(null) }
                    }
                    stopService(Intent(this@MainActivity, ConvoyLocationService::class.java))
                    setConvoyUI(null, tv, btnStart, btnEnd)
                    android.widget.Toast.makeText(this@MainActivity, "Convoy ended by creator", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        // Register location receiver
        val locationFilter = IntentFilter(ConvoyLocationService.ACTION_LOCATION)
        ContextCompat.registerReceiver(
            this, locationReceiver, locationFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Register convoy FCM receiver
        val convoyFilter = IntentFilter().apply {
            addAction("edu.temple.convoy.ACTION_CONVOY_UPDATE")
            addAction("edu.temple.convoy.ACTION_CONVOY_END")
        }
        ContextCompat.registerReceiver(
            this, convoyReceiver, convoyFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

    }


    override fun onStop() {
        super.onStop()
        unregisterReceiver(locationReceiver)
        unregisterReceiver(convoyReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(messageReceiver)
    }







}
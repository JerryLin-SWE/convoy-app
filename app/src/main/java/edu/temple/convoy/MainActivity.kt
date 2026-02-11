package edu.temple.convoy

import kotlinx.coroutines.launch
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import edu.temple.convoy.ApiClient

class MainActivity : AppCompatActivity() {


    private lateinit var store: SessionStore



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = SessionStore(this)

        lifecycleScope.launch {

            val key = store.sessionKey.first()

            if(key == null){
                showAuthChoice()
            }else{
                showMainPlaceholder()
            }


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
        //this become the map screen
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            lifecycleScope.launch {
                val username = store.username.first()
                val sessionKey = store.sessionKey.first()

                if (username != null && sessionKey != null) {
                    ApiClient.api.account(
                        action = "LOGOUT",
                        username = username,
                        password = null,
                        firstname = null,
                        lastname = null,
                        sessionKey = sessionKey
                    )
                }

                store.clearAll()
                showAuthChoice()
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
                            sessionKey = null
                        )
                        val status = response["status"]?.toString()
                        if (status == "SUCCESS") {
                            val sessionKey = response["session_key"].toString()

                            store.saveLogin(user, first, last, sessionKey)

                            android.widget.Toast.makeText(this@MainActivity, "Account created!", android.widget.Toast.LENGTH_SHORT).show()

                            dialog.dismiss()
                            showMainPlaceholder()
                        } else {
                            val message = response["message"].toString()
                            android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                        }

                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this@MainActivity, "Network error", android.widget.Toast.LENGTH_LONG).show()
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
                            lastname = null,
                            sessionKey = null
                        )

                        val status = response["status"]?.toString()

                        if (status == "SUCCESS") {

                            val sessionKey = response["session_key"].toString()

                            // We don’t get first/last from LOGIN,
                            // so store null for now
                            store.saveLogin(user, null, null, sessionKey)

                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Login successful!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()

                            dialog.dismiss()
                            showMainPlaceholder()

                        } else {
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



}
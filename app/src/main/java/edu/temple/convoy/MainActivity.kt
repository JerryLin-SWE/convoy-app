package edu.temple.convoy

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first

class MainActivity : AppCompatActivity() {


    private lateinit var store: SessionStore



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = SessionStore(this)

        lifecycleScope.launchWhenStarted {

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
        }

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            //go to Login screen
        }
    }

    private fun showMainPlaceholder(){
        setContentView(R.layout.activity_main)
        //this become the map screen
    }


}
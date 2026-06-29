package com.cloechaudron.planning

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * Écran d'aide minimal : explique les widgets, permet de forcer un
 * rafraîchissement et de tester la connexion à l'API. Tout l'usage réel se fait
 * dans les widgets (lecture seule). Aucune édition, aucun document PDF.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            sendBroadcast(
                Intent(this, RefreshWidgetProvider::class.java)
                    .setAction(RefreshWidgetProvider.ACTION_REFRESH_ALL),
            )
        }

        val output = findViewById<TextView>(R.id.diag_output)
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            output.text = getString(R.string.test_running)
            Thread {
                val d = PlanningRepository.diagnose()
                runOnUiThread { output.text = d.summary() }
            }.start()
        }
    }
}

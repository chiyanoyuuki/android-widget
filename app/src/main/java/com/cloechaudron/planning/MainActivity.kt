package com.cloechaudron.planning

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button

/**
 * Écran d'accueil minimal : sert surtout à donner une icône de lancement et à
 * "réveiller" l'app après installation. Tout se passe dans le widget.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_open_site).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PlanningWidgetProvider.SITE_URL))
            )
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            sendBroadcast(
                Intent(this, PlanningWidgetProvider::class.java).apply {
                    action = PlanningWidgetProvider.ACTION_REFRESH
                }
            )
        }
    }
}

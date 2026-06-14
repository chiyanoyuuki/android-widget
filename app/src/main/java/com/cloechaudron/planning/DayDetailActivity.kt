package com.cloechaudron.planning

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Détail d'un jour du calendrier (ouvert en tapant un jour avec événements).
 * Affiche les infos de l'événement, permet de naviguer entre les événements de
 * la même journée (◀ ▶), d'appeler / écrire un mail, d'ouvrir l'intra, et de
 * revenir au calendrier (bouton Retour ou retour système).
 */
class DayDetailActivity : Activity() {

    private var events: List<EventInfo> = emptyList()
    private var index = 0
    private var date = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_detail)
        date = intent.getStringExtra(PlanningWidgetProvider.EXTRA_DATE).orEmpty()

        findViewById<Button>(R.id.dd_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.dd_prev).setOnClickListener { step(-1) }
        findViewById<Button>(R.id.dd_next).setOnClickListener { step(1) }
        findViewById<Button>(R.id.dd_site).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PlanningWidgetProvider.SITE_URL)))
        }

        findViewById<TextView>(R.id.dd_date).text = PlanningRepository.longDate(date)

        // Chargement (cache) sur un thread worker
        Thread {
            val list = PlanningRepository.eventsOn(applicationContext, date)
            runOnUiThread {
                events = list
                index = 0
                bind()
            }
        }.start()
    }

    private fun step(delta: Int) {
        if (events.size < 2) return
        index = (index + delta + events.size) % events.size
        bind()
    }

    private fun bind() {
        val empty = findViewById<TextView>(R.id.dd_empty)
        val nav = findViewById<View>(R.id.dd_nav)
        val type = findViewById<TextView>(R.id.dd_type)
        val name = findViewById<TextView>(R.id.dd_name)
        val details = findViewById<TextView>(R.id.dd_details)
        val phone = findViewById<Button>(R.id.dd_phone)
        val mail = findViewById<Button>(R.id.dd_mail)

        if (events.isEmpty()) {
            empty.visibility = View.VISIBLE
            nav.visibility = View.GONE
            type.visibility = View.GONE
            name.visibility = View.GONE
            details.visibility = View.GONE
            phone.visibility = View.GONE
            mail.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE

        val e = events[index]
        type.visibility = View.VISIBLE
        type.text = e.typeLabel

        setOrHide(name, e.name)
        setOrHide(details, e.details)

        // Navigation entre événements du jour
        val multi = events.size > 1
        nav.visibility = if (multi) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.dd_counter).text = "${index + 1} / ${events.size}"

        if (e.phone.isNotBlank()) {
            phone.visibility = View.VISIBLE
            phone.text = "📞 ${e.phone}"
            phone.setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + e.phone.replace(" ", ""))),
                )
            }
        } else {
            phone.visibility = View.GONE
        }

        if (e.email.isNotBlank()) {
            mail.visibility = View.VISIBLE
            mail.text = "✉ ${e.email}"
            mail.setOnClickListener {
                startActivity(
                    Intent(
                        Intent.ACTION_SENDTO,
                        Uri.parse("mailto:${e.email}?subject=" + Uri.encode(e.mailSubject)),
                    ).putExtra(Intent.EXTRA_SUBJECT, e.mailSubject),
                )
            }
        } else {
            mail.visibility = View.GONE
        }
    }

    private fun setOrHide(tv: TextView, text: String) {
        if (text.isNotBlank()) {
            tv.visibility = View.VISIBLE
            tv.text = text
        } else {
            tv.visibility = View.GONE
        }
    }
}

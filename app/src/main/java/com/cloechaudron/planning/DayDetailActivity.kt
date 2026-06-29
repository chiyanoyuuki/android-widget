package com.cloechaudron.planning

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fiche d'un jour du calendrier en **lecture seule** (ouverte depuis le widget
 * calendrier ou « prochain événement »). Reprend la fiche du nouveau IntraCCB2 —
 * coordonnées, essai, lieu du mariage, horaires du jour-J et récapitulatif
 * chiffré — mais rien n'est éditable et aucun document PDF n'est généré. On peut
 * naviguer entre les événements du jour (◀ ▶) et appeler / écrire un mail.
 */
class DayDetailActivity : Activity() {

    private var cards: List<EventCard> = emptyList()
    private var index = 0
    private var date = ""
    private var initialStart = Int.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_detail)
        date = intent.getStringExtra(PlanningWidgetProvider.EXTRA_DATE).orEmpty()
        initialStart = intent.getIntExtra(EXTRA_START, Int.MIN_VALUE)

        findViewById<Button>(R.id.dd_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.dd_prev).setOnClickListener { step(-1) }
        findViewById<Button>(R.id.dd_next).setOnClickListener { step(1) }
        findViewById<TextView>(R.id.dd_date).text = FrDate.longDate(date)

        Thread {
            val list = PlanningRepository.eventsOn(applicationContext, date)
            runOnUiThread {
                cards = list
                index = list.indexOfFirst { it.startMin == initialStart }.let { if (it >= 0) it else 0 }
                bind()
            }
        }.start()
    }

    private fun step(delta: Int) {
        if (cards.size < 2) return
        index = (index + delta + cards.size) % cards.size
        bind()
    }

    private fun bind() {
        val body = findViewById<LinearLayout>(R.id.dd_body)
        val chip = findViewById<TextView>(R.id.dd_chip)
        val nav = findViewById<View>(R.id.dd_nav)
        val empty = findViewById<TextView>(R.id.dd_empty)
        body.removeAllViews()

        if (cards.isEmpty()) {
            empty.visibility = View.VISIBLE
            chip.visibility = View.GONE
            nav.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE

        val card = cards[index]
        val j = card.journee

        chip.visibility = View.VISIBLE
        chip.text = "● " + Statuts.label(j.statut, j.etape)
        chip.setTextColor(Statuts.hue(j.statut, j.etape))

        nav.visibility = if (cards.size > 1) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.dd_counter).text = "${index + 1} / ${cards.size}"

        if (j.isEssai) buildEssai(body, card, j) else buildEvent(body, card, j)
    }

    private fun buildEvent(body: LinearLayout, card: EventCard, j: Journee) {
        val coord = section(body, "Coordonnées")
        addRow(coord, "Nom", j.client.nom)
        addContacts(coord, j.client.tel, j.client.mail, card.mailSubject)
        addRow(coord, "Adresse", j.client.adresse)
        addRow(coord, "Code postal & ville", j.client.codepostal)

        if (!j.statut.isPerso) {
            val essai = listOf(j.essai.date, j.essai.heure, j.essai.lieu).filter { !it.isNullOrBlank() }
            if (essai.isNotEmpty()) {
                val s = section(body, "Essai")
                addRow(s, "Date", j.essai.date)
                addRow(s, "Heure", j.essai.heure)
                addRow(s, "Lieu", j.essai.lieu)
            }
            if (hasMariageInfo(j)) {
                val s = section(body, "Lieu du mariage")
                addRow(s, "Domaine", j.mariage.domaine)
                addRow(s, "Cérémonie", j.mariage.ceremonie)
                addRow(s, "Adresse", j.mariage.adresse)
                addRow(s, "Code postal & ville", j.mariage.codepostal)
            }
        }

        if (card.horairesLine.isNotBlank() || !j.planning?.date.isNullOrBlank()) {
            val s = section(body, "Jour-J")
            if (!j.planning?.date.isNullOrBlank()) addRow(s, "Planning du", j.planning?.date)
            if (card.horairesLine.isNotBlank()) addLine(s, card.horairesLine)
        }

        if (j.devis != null) buildRecap(body, j)
    }

    private fun buildEssai(body: LinearLayout, card: EventCard, j: Journee) {
        val s = section(body, "Fiche essai")
        addRow(s, "Nom", j.client.nom)
        addContacts(s, j.client.tel, j.client.mail, card.mailSubject)
        addRow(s, "Essai", listOf(j.essai.date, j.essai.heure, j.essai.lieu).filter { !it.isNullOrBlank() }.joinToString(" "))
        addRow(s, "Domaine", j.mariage.domaine)
        addRow(s, "Cérémonie", j.mariage.ceremonie)
    }

    private fun buildRecap(body: LinearLayout, j: Journee) {
        val s = section(body, "Récapitulatif")
        val prestas = j.devis?.prestas ?: emptyList()
        recapHeader(s)
        for (p in Pricing.recapLines(j)) recapRow(s, p)

        val total = Pricing.myTotal(prestas)
        val paid = Pricing.paid(j)
        totalRow(s, "Total", total)
        totalRow(s, "Payé", paid)
        totalRow(s, "Reste à payer", total - paid)
        if (Pricing.hasProvider(prestas)) totalRow(s, "Prestataire (réglé à part)", Pricing.providerTotal(prestas))
        val cash = j.argentLiquide ?: 0.0
        if (cash > 0) totalRow(s, "Argent liquide (pourboire)", cash)
    }

    // --- Construction des vues (lecture seule) -------------------------------

    private fun section(body: LinearLayout, title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.section_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(Palette.BRAND_STRONG)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        body.addView(card)
        return card
    }

    private fun addRow(parent: LinearLayout, label: String, value: String?) {
        if (value.isNullOrBlank()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) }
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Palette.TEXT_MUTED)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(dp(124), WRAP_CONTENT)
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(Palette.TEXT)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        parent.addView(row)
    }

    private fun addLine(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(Palette.TEXT)
            textSize = 14f
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun addContacts(parent: LinearLayout, tel: String?, mail: String?, subject: String) {
        val phone = tel?.takeIf { it.isNotBlank() }
        val email = mail?.takeIf { it.isNotBlank() }
        if (phone == null && email == null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        if (phone != null) {
            row.addView(Button(this).apply {
                text = "📞 $phone"
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.replace(" ", ""))))
                }
            })
        }
        if (email != null) {
            row.addView(Button(this).apply {
                text = "✉ Mail"
                setOnClickListener {
                    startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email?subject=" + Uri.encode(subject)))
                            .putExtra(Intent.EXTRA_SUBJECT, subject),
                    )
                }
            })
        }
        parent.addView(row)
    }

    // --- Tableau du récapitulatif --------------------------------------------

    private fun recapHeader(parent: LinearLayout) {
        val row = recapRowContainer(dp(6))
        row.addView(cell("Qté", 0.6f, Palette.TEXT_MUTED, bold = true))
        row.addView(cell("Prestation", 2.2f, Palette.TEXT_MUTED, bold = true))
        row.addView(cell("Réduc", 0.7f, Palette.TEXT_MUTED, bold = true))
        row.addView(cell("Total", 1.1f, Palette.TEXT_MUTED, bold = true, end = true))
        parent.addView(row)
    }

    private fun recapRow(parent: LinearLayout, p: Presta) {
        val reduc = if (p.reduc != null && p.reduc != 0.0) "${Money.js(p.reduc)}%" else ""
        val row = recapRowContainer(dp(3))
        row.addView(cell(p.qteLabel(), 0.6f, Palette.TEXT))
        row.addView(cell(p.nom, 2.2f, Palette.TEXT))
        row.addView(cell(reduc, 0.7f, Palette.TEXT))
        row.addView(cell(Pricing.lineLabel(p), 1.1f, Palette.TEXT, end = true))
        parent.addView(row)
    }

    private fun totalRow(parent: LinearLayout, label: String, value: Double) {
        val row = recapRowContainer(dp(4))
        row.addView(cell(label, 1f, Palette.TEXT))
        row.addView(cell(Money.euro(value), 0f, Palette.TEXT, bold = true, end = true, wrap = true))
        parent.addView(row)
    }

    private fun recapRowContainer(top: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top }
    }

    private fun cell(
        text: String,
        weight: Float,
        color: Int,
        bold: Boolean = false,
        end: Boolean = false,
        wrap: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 13f
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (end) gravity = Gravity.END
        layoutParams = if (wrap) {
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        } else {
            LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)
        }
    }

    private fun hasMariageInfo(j: Journee): Boolean =
        !j.mariage.domaine.isNullOrBlank() || !j.mariage.adresse.isNullOrBlank() ||
            !j.mariage.codepostal.isNullOrBlank() || !j.mariage.ceremonie.isNullOrBlank()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_START = "com.cloechaudron.planning.EXTRA_START"
    }
}

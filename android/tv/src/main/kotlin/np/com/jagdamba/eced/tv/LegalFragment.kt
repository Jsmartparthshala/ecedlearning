package np.com.jagdamba.eced.tv

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import np.com.jagdamba.eced.core.model.AppDocument

/**
 * Privacy policy, terms, data handling and open source notices.
 *
 * The documents are not compiled into the application. They live in
 * `app_documents` and are edited in the ops console, because a privacy policy
 * that can only change when forty sideloaded televisions each accept an update
 * is a policy that stays wrong for however long that takes - and it is the one
 * document class where being out of date is a legal problem rather than a
 * cosmetic one.
 *
 * Two states, one screen. A list of titles, and a reader. BACK from the reader
 * returns to the list rather than leaving the screen, which is the behaviour
 * anyone who has ever used a television expects and which no back stack entry is
 * needed to provide.
 *
 * What is on screen is cached to disk after every successful fetch. A school
 * whose link is down still gets the wording it last saw, which is the honest
 * fallback: better than a blank screen, and it cannot claim to be current
 * because the reader prints the version and date it was published under.
 */
class LegalFragment : Fragment() {

    private lateinit var list: LinearLayout
    private lateinit var reader: NestedScrollView
    private lateinit var readerTitle: TextView
    private lateinit var readerMeta: TextView
    private lateinit var readerBody: TextView
    private lateinit var empty: TextView

    private var documents: List<AppDocument> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_legal, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.legal_list)
        reader = view.findViewById(R.id.legal_reader)
        readerTitle = view.findViewById(R.id.legal_reader_title)
        readerMeta = view.findViewById(R.id.legal_reader_meta)
        readerBody = view.findViewById(R.id.legal_reader_body)
        empty = view.findViewById(R.id.legal_empty)

        // Whatever was read last time, drawn immediately. The fetch below
        // replaces it if it succeeds; on a school link that takes a moment, and
        // an empty screen followed by a filled one reads as a fault.
        cached()?.let { render(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            val fetched = EcedApp.instance.catalog.documents()
            if (fetched.isNotEmpty()) {
                cache(fetched)
                render(fetched)
            } else if (documents.isEmpty()) {
                empty.visibility = View.VISIBLE
            }
        }
    }

    /** True if the reader was showing and has been closed. */
    fun onBack(): Boolean {
        if (reader.visibility != View.VISIBLE) return false
        showList()
        return true
    }

    private fun render(docs: List<AppDocument>) {
        documents = docs
        empty.visibility = View.GONE
        list.removeAllViews()

        val inflater = LayoutInflater.from(requireContext())
        docs.forEach { doc ->
            val row = inflater.inflate(R.layout.row_legal, list, false)
            row.findViewById<TextView>(R.id.rl_title).text = doc.titleEn
            row.findViewById<TextView>(R.id.rl_meta).text = meta(doc)
            row.setOnClickListener { open(doc) }
            list.addView(row)
        }
        list.getChildAt(0)?.requestFocus()
    }

    /**
     * The version and date a document was published under, printed on the row
     * and again at the top of the reader.
     *
     * Not decoration. A school that reads a policy needs to know which one it
     * read, and a placeholder that has not been replaced yet says `placeholder`
     * here - which is a great deal harder to ship by accident than filler that
     * looks like a finished document.
     */
    private fun meta(doc: AppDocument): String {
        val parts = listOfNotNull(
            doc.version.takeIf { it.isNotBlank() },
            doc.effectiveOn?.take(10),
        )
        return if (parts.isEmpty()) "" else parts.joinToString(" · ")
    }

    private fun open(doc: AppDocument) {
        readerTitle.text = doc.titleEn
        readerMeta.text = meta(doc)
        readerMeta.visibility = if (readerMeta.text.isNullOrBlank()) View.GONE else View.VISIBLE
        readerBody.text = doc.bodyEn
        reader.scrollTo(0, 0)

        list.visibility = View.GONE
        reader.visibility = View.VISIBLE
        // The scroller itself takes focus, so DOWN scrolls the page. There is
        // nothing focusable inside a document and nothing should be - a wall of
        // text broken into focusable paragraphs is worse to read with a remote,
        // not better.
        reader.requestFocus()
    }

    private fun showList() {
        reader.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.getChildAt(0)?.requestFocus()
    }

    // ------------------------------------------------------------------ cache

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cache(docs: List<AppDocument>) {
        runCatching { prefs().edit().putString(KEY, json.encodeToString(docs)).apply() }
    }

    private fun cached(): List<AppDocument>? {
        val raw = prefs().getString(KEY, null) ?: return null
        // A cache written by an older build with a different shape is not worth
        // a crash on the legal screen of a television in a classroom.
        return runCatching { json.decodeFromString<List<AppDocument>>(raw) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val PREFS = "legal"
        const val KEY = "documents"
        val json = Json { ignoreUnknownKeys = true }
    }
}

package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * Lessons saved to this television.
 *
 * Offline caching is not implemented yet, so this is an honest empty state rather
 * than a fake list. It exists now because the sidebar entry is part of the
 * navigation the product promises, and because an empty state that explains
 * itself is better than a section that appears later out of nowhere.
 */
class DownloadsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_downloads, container, false)
}

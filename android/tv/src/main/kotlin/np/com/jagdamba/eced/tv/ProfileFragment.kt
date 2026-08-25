package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Who this television belongs to.
 *
 * There is no sign in anywhere in this product by design: the central office
 * activates the device once and it belongs to the school, not to a person. So
 * this is an honest empty state that names the school rather than a pretend
 * account screen. The `profiles` table already carries a teacher/admin/parent
 * role for when per-teacher progress arrives.
 */
class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val school = EcedApp.instance.devices.cachedSchoolName()
        view.findViewById<TextView>(R.id.profile_school).text =
            school?.uppercase() ?: getString(R.string.chip_unclaimed).uppercase()
    }
}

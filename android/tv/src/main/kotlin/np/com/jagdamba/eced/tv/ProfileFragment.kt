package np.com.jagdamba.eced.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Who this television belongs to.
 *
 * There is no sign in anywhere in this product by design: the central office
 * activates the device once and assigns it to a teacher, and the teacher never
 * types a password into a television with a remote control.
 *
 * So this screen has two honest states rather than one empty one. With a teacher
 * assigned it names them and their class. Without one it says the television
 * belongs to the school, which is the right answer for a hall or a shared room
 * and not a failure.
 */
class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Paint from cache first so the screen is never blank, then refresh. An
        // assignment made in the ops console a minute ago should show up without
        // restarting the television, but not at the cost of a spinner.
        render(view)
        viewLifecycleOwner.lifecycleScope.launch {
            EcedApp.instance.devices.refreshSchoolName()
            if (isAdded) render(view)
        }
    }

    private fun render(view: View) {
        val devices = EcedApp.instance.devices
        val school = devices.cachedSchoolName()
        val teacher = devices.cachedTeacherName()
        val role = devices.cachedTeacherRole()

        // The class is a real row the office manages; teachers.role is free text
        // somebody typed. When both exist the class is the more trustworthy of
        // the two, so it wins.
        val klass = devices.cachedClassLabel()?.takeIf { it.isNotBlank() }

        view.findViewById<TextView>(R.id.profile_school).text = when {
            teacher != null -> getString(R.string.profile_teacher_kicker).uppercase()
            else -> school?.uppercase() ?: getString(R.string.chip_unclaimed).uppercase()
        }

        if (teacher.isNullOrBlank()) {
            // A television can have a class and no teacher - the office sets the
            // room up before anyone is assigned to it, and some rooms never get
            // one. Naming the class is more use than saying nothing about it.
            view.findViewById<TextView>(R.id.profile_name).text =
                klass ?: getString(R.string.profile_empty_title)
            view.findViewById<TextView>(R.id.profile_role).text =
                if (klass != null) getString(R.string.profile_class_body, klass)
                else getString(R.string.profile_empty_body)
            return
        }

        view.findViewById<TextView>(R.id.profile_name).text = teacher
        // The class label is optional in the ops console, so the sentence has to
        // read correctly with and without it rather than trailing a stray comma.
        val descriptor = klass ?: role
        view.findViewById<TextView>(R.id.profile_role).text =
            if (descriptor.isNullOrBlank()) getString(R.string.profile_role_blank)
            else getString(R.string.profile_role_known, descriptor)
    }
}

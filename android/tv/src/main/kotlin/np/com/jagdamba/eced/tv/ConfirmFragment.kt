package np.com.jagdamba.eced.tv

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/**
 * Full-screen confirmation, for the two questions this app ever has to ask.
 *
 * This replaces `androidx.appcompat.app.AlertDialog`, which could never have
 * worked here: AppCompat dialogs require the host activity's theme to descend
 * from `Theme.AppCompat`, and every activity in this app is themed
 * `Theme.Leanback`. Building one threw IllegalStateException the instant the
 * teacher pressed OK, so "Unpair this television" and "Update available" both
 * killed the process. On a television that reads as the button doing nothing -
 * the app simply disappears and the launcher comes back.
 *
 * GuidedStepSupportFragment is the Leanback answer to the same problem and is a
 * better fit besides: full-screen, large type readable from across a classroom,
 * and D-pad navigation for free. It costs no new dependency - leanback is
 * already here for the browse shell.
 *
 * [onProvideTheme] is required because the host activities are `Theme.Leanback`
 * rather than `Theme.Leanback.GuidedStep`; without it the guidance panel
 * inflates unstyled.
 *
 * Results come back through the fragment result API rather than a listener
 * interface, so the caller survives the process death that a low-memory box can
 * inflict while the teacher is still reading the question.
 */
class ConfirmFragment : GuidedStepSupportFragment() {

    override fun onProvideTheme(): Int = R.style.Theme_Eced_GuidedStep

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            requireArguments().getString(ARG_TITLE).orEmpty(),
            requireArguments().getString(ARG_BODY).orEmpty(),
            requireArguments().getString(ARG_BREADCRUMB).orEmpty(),
            null,
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val args = requireArguments()

        // Cancel is deliberately first, so the destructive action is never the
        // one sitting under the cursor when the panel opens. A remote in a
        // classroom gets pressed by people who are not reading.
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_CANCEL)
                .title(args.getString(ARG_CANCEL).orEmpty())
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_CONFIRM)
                .title(args.getString(ARG_CONFIRM).orEmpty())
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val key = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()
        // FragmentManager's own method rather than the fragment-ktx extension of
        // the same name: fragment-ktx is not a dependency here, and pulling one
        // in for a single call is not worth it. leanback already brings the base
        // fragment artifact, which is where this lives.
        parentFragmentManager.setFragmentResult(
            key, bundleOf(RESULT_CONFIRMED to (action.id == ID_CONFIRM))
        )
        parentFragmentManager.popBackStack()
    }

    companion object {
        private const val ID_CANCEL = 0L
        private const val ID_CONFIRM = 1L

        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"
        private const val ARG_BREADCRUMB = "breadcrumb"
        private const val ARG_CONFIRM = "confirm"
        private const val ARG_CANCEL = "cancel"
        private const val ARG_REQUEST_KEY = "request_key"

        /** Key inside the result bundle. True when the positive action was taken. */
        const val RESULT_CONFIRMED = "confirmed"

        /**
         * Show the panel over [containerId].
         *
         * @param requestKey the caller listens for this with setFragmentResultListener.
         */
        fun show(
            fm: FragmentManager,
            containerId: Int,
            requestKey: String,
            title: String,
            body: String,
            confirm: String,
            cancel: String,
            breadcrumb: String = "",
        ) {
            val fragment = ConfirmFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_BODY to body,
                    ARG_BREADCRUMB to breadcrumb,
                    ARG_CONFIRM to confirm,
                    ARG_CANCEL to cancel,
                    ARG_REQUEST_KEY to requestKey,
                )
            }
            // add() rather than addAsRoot(): this sits on top of the screen that
            // asked, and popping it returns there.
            GuidedStepSupportFragment.add(fm, fragment, containerId)
        }
    }
}

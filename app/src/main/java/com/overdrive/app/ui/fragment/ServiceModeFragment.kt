package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow

/**
 * Service Mode Settings pane.
 *
 * Hosts the shared /service-mode page through WebViewFragment so native
 * Settings and the standalone WebUI use the same controls and API.
 */
class ServiceModeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).apply {
            id = HOST_ID
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(HOST_ID) != null) return
        val webFragment = WebViewFragment().apply {
            arguments = Bundle().apply {
                putString(WebViewFragment.ARG_PAGE_PATH, "/service-mode")
            }
        }
        childFragmentManager.commitNow {
            replace(HOST_ID, webFragment, TAG_WEB)
        }
    }

    companion object {
        private const val HOST_ID = 0x0F100009
        private const val TAG_WEB = "service_mode_web"
    }
}

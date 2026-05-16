package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.overdrive.app.R

/**
 * Main recording fragment with tabs for Controls and Library.
 */
class RecordingFragment : Fragment() {
    
    private val recordingViewModel: RecordingViewModel by activityViewModels()
    
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recording, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        tabLayout = view.findViewById(R.id.tabLayout)
        viewPager = view.findViewById(R.id.viewPager)
        
        setupViewPager()
    }
    
    private fun setupViewPager() {
        viewPager.adapter = RecordingPagerAdapter(this)
        
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.recording_tab_controls)
                1 -> getString(R.string.recording_tab_library)
                else -> ""
            }
        }.attach()
    }
    
    private class RecordingPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2
        
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> RecordingControlsFragment()
                1 -> RecordingLibraryFragment()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
}

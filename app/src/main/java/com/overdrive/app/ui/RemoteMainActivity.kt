package com.overdrive.app.ui

/**
 * A distinct component for the private Remote Dev View display.
 *
 * The implementation intentionally inherits the real MainActivity so the
 * remote session exercises the same fragments, WebViews, dialogs, and input
 * handlers. Its separate manifest task affinity lets Android keep it on the
 * app-owned virtual display without moving or replacing the physical task.
 */
class RemoteMainActivity : MainActivity()

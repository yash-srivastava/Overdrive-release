package com.overdrive.app.ui.dialog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.overdrive.app.R
import com.overdrive.app.ui.view.RoiDrawingView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dialog for drawing ROI (Region of Interest) polygons on camera preview.
 * 
 * Features:
 * - Camera selector dropdown (1-4)
 * - Live camera snapshot as background
 * - Touch-based polygon drawing
 * - Save/Clear/Undo controls
 * - Local persistence via SharedPreferences
 */
class RoiDrawingDialogFragment : DialogFragment() {
    
    companion object {
        const val TAG = "RoiDrawingDialog"
        private const val PREFS_NAME = "sentry_roi_prefs"
        private const val KEY_ROI_DATA = "roi_data"
        
        fun newInstance(): RoiDrawingDialogFragment {
            return RoiDrawingDialogFragment()
        }
    }
    
    private lateinit var spinnerCamera: Spinner
    private lateinit var ivPreview: ImageView
    private lateinit var roiDrawingView: RoiDrawingView
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    
    private var currentCameraId = 1
    private var snapshotJob: Job? = null
    
    // Callback for when ROI is saved
    var onRoiSavedListener: ((cameraId: Int, points: FloatArray) -> Unit)? = null
    
    // Existing ROI data per camera (loaded from local storage)
    private val existingRois = mutableMapOf<Int, List<PointF>>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Overdrive_FullScreenDialog)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_roi_drawing, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        
        // Load saved ROI data from local storage
        loadRoiFromPrefs()
        
        setupCameraSelector()
        setupButtons()
        
        // Load existing ROI for current camera (if any)
        existingRois[currentCameraId]?.let { points ->
            roiDrawingView.setRoi(points)
        }
        
        // Load initial camera snapshot
        loadCameraSnapshot(currentCameraId)
    }
    
    override fun onStart() {
        super.onStart()
        // Make dialog full screen
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        snapshotJob?.cancel()
    }
    
    private fun initViews(view: View) {
        spinnerCamera = view.findViewById(R.id.spinnerCamera)
        ivPreview = view.findViewById(R.id.ivPreview)
        roiDrawingView = view.findViewById(R.id.roiDrawingView)
        btnUndo = view.findViewById(R.id.btnUndo)
        btnClear = view.findViewById(R.id.btnClear)
        btnSave = view.findViewById(R.id.btnSave)
        btnCancel = view.findViewById(R.id.btnCancel)
    }
    
    private fun setupCameraSelector() {
        val cameraNames = arrayOf(
            getString(R.string.roi_camera_front),
            getString(R.string.roi_camera_right),
            getString(R.string.roi_camera_rear),
            getString(R.string.roi_camera_left)
        )
        
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            cameraNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCamera.adapter = adapter
        
        spinnerCamera.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newCameraId = position + 1
                if (newCameraId != currentCameraId) {
                    // Save current ROI before switching
                    if (roiDrawingView.isValidRoi()) {
                        existingRois[currentCameraId] = roiDrawingView.getRoi()
                    }
                    
                    currentCameraId = newCameraId
                    loadCameraSnapshot(currentCameraId)
                    
                    // Load existing ROI for new camera
                    val existingRoi = existingRois[currentCameraId]
                    if (existingRoi != null) {
                        roiDrawingView.setRoi(existingRoi)
                    } else {
                        roiDrawingView.clearRoi()
                    }
                }
            }
            
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }
    
    private fun setupButtons() {
        btnUndo.setOnClickListener {
            roiDrawingView.undoLastPoint()
        }
        
        btnClear.setOnClickListener {
            roiDrawingView.clearRoi()
            // Also clear from local storage
            existingRois.remove(currentCameraId)
            saveRoiToPrefs()
        }
        
        btnSave.setOnClickListener {
            if (roiDrawingView.isValidRoi()) {
                // Save to local map
                existingRois[currentCameraId] = roiDrawingView.getRoi()
                
                // Save to SharedPreferences
                saveRoiToPrefs()
                
                // Notify listener (sends to daemon)
                val points = roiDrawingView.getRoiAsFloatArray()
                onRoiSavedListener?.invoke(currentCameraId, points)
                
                Toast.makeText(context, getString(R.string.toast_roi_saved_for_camera, currentCameraId), Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Toast.makeText(
                    context,
                    getString(R.string.toast_roi_min_points, roiDrawingView.minPoints),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        btnCancel.setOnClickListener {
            dismiss()
        }
    }
    
    // ==================== LOCAL PERSISTENCE ====================
    
    private fun loadRoiFromPrefs() {
        try {
            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
            val jsonStr = prefs.getString(KEY_ROI_DATA, null) ?: return
            
            val json = JSONObject(jsonStr)
            existingRois.clear()
            
            json.keys().forEach { key ->
                val cameraId = key.toIntOrNull() ?: return@forEach
                val pointsArray = json.optJSONArray(key) ?: return@forEach
                
                val points = mutableListOf<PointF>()
                for (i in 0 until pointsArray.length()) {
                    val p = pointsArray.optJSONObject(i) ?: continue
                    points.add(PointF(
                        p.optDouble("x", 0.0).toFloat(),
                        p.optDouble("y", 0.0).toFloat()
                    ))
                }
                
                if (points.isNotEmpty()) {
                    existingRois[cameraId] = points
                }
            }
        } catch (e: Exception) {
            // Failed to load, start fresh
        }
    }
    
    private fun saveRoiToPrefs() {
        try {
            val json = JSONObject()
            
            existingRois.forEach { (cameraId, points) ->
                val pointsArray = JSONArray()
                points.forEach { point ->
                    pointsArray.put(JSONObject().apply {
                        put("x", point.x.toDouble())
                        put("y", point.y.toDouble())
                    })
                }
                json.put(cameraId.toString(), pointsArray)
            }
            
            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
            prefs.edit().putString(KEY_ROI_DATA, json.toString()).apply()
        } catch (e: Exception) {
            // Failed to save
        }
    }
    
    private fun loadCameraSnapshot(cameraId: Int) {
        snapshotJob?.cancel()
        snapshotJob = CoroutineScope(Dispatchers.IO).launch {
            var bitmap: Bitmap? = null
            
            // First try to load live snapshot from HTTP server
            try {
                val connection = com.overdrive.app.util.DaemonHttpClient.open(
                    "/snapshot/$cameraId", "GET", 2000, 2000)
                bitmap = BitmapFactory.decodeStream(connection.inputStream)
                connection.disconnect()
            } catch (e: Exception) {
                // Live snapshot not available, try dummy image
            }
            
            // If live snapshot failed, load dummy image from assets
            if (bitmap == null) {
                try {
                    val assetName = "dummy_camera_$cameraId.jpg"
                    context?.assets?.open(assetName)?.use { stream ->
                        bitmap = BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    // Dummy image not found either
                }
            }
            
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    ivPreview.setImageBitmap(bitmap)
                    ivPreview.scaleType = ImageView.ScaleType.FIT_CENTER
                } else {
                    showPlaceholder(cameraId)
                }
            }
        }
    }
    
    private fun showPlaceholder(cameraId: Int) {
        // Create a simple colored placeholder with camera label
        val cameraLabels = arrayOf("Front", "Right", "Rear", "Left")
        val label = cameraLabels.getOrElse(cameraId - 1) { "Camera $cameraId" }
        
        // Create a bitmap with the camera label
        val width = 640
        val height = 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Dark background
        canvas.drawColor(Color.parseColor("#1a1a2e"))
        
        // Draw camera icon placeholder
        val iconPaint = Paint().apply {
            color = Color.parseColor("#444466")
            textSize = 120f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("📷", width / 2f, height / 2f - 40, iconPaint)
        
        // Draw camera label
        val textPaint = Paint().apply {
            color = Color.parseColor("#888899")
            textSize = 48f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("Camera $cameraId - $label", width / 2f, height / 2f + 60, textPaint)
        
        // Draw hint
        val hintPaint = Paint().apply {
            color = Color.parseColor("#666677")
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("(Start Camera Daemon for live preview)", width / 2f, height / 2f + 120, hintPaint)
        
        ivPreview.setImageBitmap(bitmap)
        ivPreview.scaleType = ImageView.ScaleType.FIT_CENTER
    }
    
    /**
     * Set existing ROI data for all cameras.
     * Can be called before or after view creation.
     * Also saves to local storage.
     */
    fun setExistingRois(rois: Map<Int, List<PointF>>) {
        existingRois.clear()
        existingRois.putAll(rois)
        
        // Save to local storage
        saveRoiToPrefs()
        
        // Only update view if it's already created
        if (::roiDrawingView.isInitialized) {
            existingRois[currentCameraId]?.let { points ->
                roiDrawingView.setRoi(points)
            }
        }
    }
}

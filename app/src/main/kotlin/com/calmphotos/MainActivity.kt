package com.calmphotos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.calmphotos.ui.CalmPhotosApp
import com.calmphotos.ui.PhotosViewModel

class MainActivity : ComponentActivity() {
    private val vm: PhotosViewModel by viewModels()
    private var granted by mutableStateOf(false)
    private var partialOnly by mutableStateOf(false)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private fun refresh() {
        val full = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
        // Android 14's "Select photos" grants only this one. The app still works,
        // but it is seeing a handful of pictures rather than the whole library,
        // and it should say so rather than pretend that is everything.
        val partial = android.os.Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") ==
            PackageManager.PERMISSION_GRANTED
        granted = full || partial
        partialOnly = !full && partial
        if (granted) vm.permissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            CalmPhotosApp(
                vm = vm,
                hasPermission = granted,
                partialAccess = partialOnly,
                onRequestPermission = {
                    ask.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.ACCESS_MEDIA_LOCATION,
                    ))
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val wasGranted = granted
        refresh()
        // Coming back from the system picker or Settings: the selection may have
        // changed under us, so re-read rather than trusting the cached list.
        if (granted && wasGranted) vm.reload()
    }
}

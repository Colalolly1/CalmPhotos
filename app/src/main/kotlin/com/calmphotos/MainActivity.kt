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

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private fun refresh() {
        granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.permissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            CalmPhotosApp(
                vm = vm,
                hasPermission = granted,
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

    override fun onResume() { super.onResume(); refresh() }
}

package com.calmphotos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.calmphotos.ui.CalmPhotosApp
import com.calmphotos.ui.NoIndication
import com.calmphotos.ui.PageTurner
import com.calmphotos.ui.PhotosViewModel

class MainActivity : ComponentActivity() {
    private val vm: PhotosViewModel by viewModels()
    private val pages = PageTurner()
    private var granted by mutableStateOf(false)
    private var partialOnly by mutableStateOf(false)
    // Set when a volume press turned a page, so its release is swallowed too.
    private var keyTurned = false

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
            // No press highlight anywhere: see NoIndication.
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                CalmPhotosApp(
                    vm = vm,
                    hasPermission = granted,
                    partialAccess = partialOnly,
                    pages = pages,
                    onRequestPermission = {
                        ask.launch(arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                        ))
                    },
                )
            }
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

    // Volume down turns forward, volume up back, one press one page: holding the
    // key doesn't race through the library. When the screen in front has no use
    // for it (a sheet is open, a video is playing) it stays a volume key.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val forward = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0) keyTurned = pages.turn(forward)
        return keyTurned || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (volume && keyTurned) {
            keyTurned = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

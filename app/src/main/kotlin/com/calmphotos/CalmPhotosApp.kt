package com.calmphotos

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.calmphotos.ui.Fonts

class CalmPhotosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(VideoFrameDecoder.Factory()) }
                .crossfade(false)   // E-Ink: no fades, just draw it
                .build()
        )
    }
}

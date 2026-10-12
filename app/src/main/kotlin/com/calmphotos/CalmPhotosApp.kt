package com.calmphotos

import android.app.Application
import android.net.Uri
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.calmphotos.data.ThumbnailFetcher
import com.calmphotos.ui.Fonts

class CalmPhotosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    add(ThumbnailFetcher.Factory(), Uri::class.java)   // ahead of Coil's own, falls back to them
                    add(VideoFrameDecoder.Factory())
                }
                .allowRgb565(true)   // half the memory, and nothing a greyscale screen can show is lost
                .crossfade(false)   // E-Ink: no fades, just draw it
                .build()
        )
    }
}

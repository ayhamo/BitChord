package com.music.bitchord.fork

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import com.music.bitchord.data.Http

/**
 * The media session's covers (notification, lock screen) through the app's HTTP client (fork-only).
 * A server's song carries no login in its cover address, because any app can read that address from
 * the session; the client adds the login per request, and answers a kept cover from the phone.
 */
@OptIn(UnstableApi::class)
internal fun forkBitmapLoader(context: Context): BitmapLoader = CacheBitmapLoader(
    DataSourceBitmapLoader.Builder(context)
        .setDataSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(Http.client)))
        .build(),
)

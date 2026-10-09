package com.p2psecure.share

import android.app.Application
import android.content.Context

class ShareApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }
}

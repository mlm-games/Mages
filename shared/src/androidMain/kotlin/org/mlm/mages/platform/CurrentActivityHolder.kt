package org.mlm.mages.platform

import android.app.Activity
import java.lang.ref.WeakReference

object CurrentActivityHolder {
    private var ref: WeakReference<Activity>? = null

    var activity: Activity?
        get() = ref?.get()
        set(value) {
            ref = if (value == null) null else WeakReference(value)
        }
}

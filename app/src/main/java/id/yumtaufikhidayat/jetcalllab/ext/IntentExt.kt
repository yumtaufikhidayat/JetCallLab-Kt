package id.yumtaufikhidayat.jetcalllab.ext

import android.content.Intent
import id.yumtaufikhidayat.jetcalllab.service.CallService

fun Intent?.handleIntent() {
    when (this?.action) {
        CallService.ACTION_SHOW_CALL -> {
            android.util.Log.d("MainActivity", "SHOW_CALL")
            // no-op (compose screen already there)
        }
        CallService.ACTION_END_CALL -> {
            android.util.Log.d("MainActivity", "END_CALL")
            // (later if you want to) trigger end call
        }
    }
}
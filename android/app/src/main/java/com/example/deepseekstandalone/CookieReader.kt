package com.example.deepseekstandalone

import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Read the WebView's cookies as a request header value.
 *
 * Page script cannot do this: the session cookie is HttpOnly, which is precisely why
 * it is safe from page code and invisible to `document.cookie`. CookieManager is the
 * only way to see it, and it must be called on the main thread.
 */
object CookieReader {

    /** Every cookie that applies to [url], joined as "a=1; b=2", or "" if none. */
    fun headerFor(view: WebView, url: String): String {
        return try {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            val header = manager.getCookie(url) ?: return ""
            // The manager already returns the "name=value; ..." form.
            header
        } catch (e: Exception) {
            ""
        }
    }
}

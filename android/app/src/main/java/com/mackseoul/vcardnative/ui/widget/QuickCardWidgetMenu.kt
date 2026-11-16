package com.mackseoul.vcardnative.ui.widget

import com.mackseoul.vcardnative.ui.profile.ProfileDestination

enum class QuickCardWidgetMenuDestination(val requestKey: String, val destination: ProfileDestination) {
    MyCard("my-card", ProfileDestination.MyCard),
    Share("share", ProfileDestination.Home),
    Profile("profile", ProfileDestination.Profile);

    companion object {
        fun fromRequestKey(key: String?): QuickCardWidgetMenuDestination? =
            entries.firstOrNull { it.requestKey == key }
    }
}

object QuickCardWidgetClickIdentity {
    fun body(widgetId: Int): String = "vcardnative://quick-card-widget/$widgetId"
    fun menu(widgetId: Int): String = "vcardnative://quick-card-widget/menu/$widgetId"
}
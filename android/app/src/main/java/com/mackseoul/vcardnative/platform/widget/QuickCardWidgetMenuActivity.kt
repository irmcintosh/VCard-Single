package com.mackseoul.vcardnative.platform.widget

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.MainActivity
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.Theme
import com.mackseoul.vcardnative.ui.theme.VCardTheme
import com.mackseoul.vcardnative.ui.widget.QuickCardWidgetMenuDestination

class QuickCardWidgetMenuActivity : ComponentActivity() {
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        window.setGravity(Gravity.CENTER)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setContent {
            BackHandler { finish() }
            VCardTheme(Appearance(theme = if (dark) Theme.dark else Theme.light)) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(8.dp)) {
                        QuickCardWidgetMenuDestination.entries.forEach { destination ->
                            val (label, icon) = when (destination) {
                                QuickCardWidgetMenuDestination.MyCard -> R.string.my_card to R.drawable.ic_card
                                QuickCardWidgetMenuDestination.Share -> R.string.share to R.drawable.ic_qr
                                QuickCardWidgetMenuDestination.Profile -> R.string.profile to R.drawable.ic_person
                            }
                            TextButton(onClick = { open(destination) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                                    Text(stringResource(label), modifier = Modifier.padding(start = 12.dp),
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
        val density = resources.displayMetrics.density
        val width = minOf((240 * density).toInt(), resources.displayMetrics.widthPixels - (32 * density).toInt())
            .coerceAtLeast(1)
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun open(destination: QuickCardWidgetMenuDestination) {
        if (opening) return
        opening = true
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_WIDGET_MENU_SELECTION
                putExtra(MainActivity.EXTRA_WIDGET_MENU_DESTINATION, destination.requestKey)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.widget_menu_launch_failed, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.widget_menu_launch_failed, Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }
}
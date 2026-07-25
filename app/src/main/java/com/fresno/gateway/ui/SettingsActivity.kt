package com.fresno.gateway.ui

import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.fresno.gateway.R
import com.fresno.gateway.util.Prefs

/** Settings screen: simple focusable checkboxes, D-pad navigable. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bind(R.id.cb_flip_answer, Prefs.flipAnswer(this)) { Prefs.setFlipAnswer(this, it) }
        bind(R.id.cb_flip_hangup, Prefs.flipHangup(this)) { Prefs.setFlipHangup(this, it) }
        bind(R.id.cb_ring, Prefs.ringFresno(this)) { Prefs.setRingFresno(this, it) }
        bind(R.id.cb_reconnect, Prefs.autoReconnect(this)) { Prefs.setAutoReconnect(this, it) }
        bind(R.id.cb_native, Prefs.nativeHfp(this)) { Prefs.setNativeHfp(this, it) }
    }

    private fun bind(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val cb = findViewById<CheckBox>(id)
        cb.isChecked = initial
        cb.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}

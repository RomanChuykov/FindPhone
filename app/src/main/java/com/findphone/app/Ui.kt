package com.findphone.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

fun Context.toast(@StringRes resId: Int) {
    Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
}

/** Додає відступи під системні панелі (потрібно для Android 15 з крайовим режимом). */
fun View.applySystemBarsPadding() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
}

/** Заповнює Spinner списком і викликає [onSelect], коли користувач обирає пункт. */
fun Spinner.bind(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val a = ArrayAdapter(context, android.R.layout.simple_spinner_item, items)
    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    adapter = a
    setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)), false)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelect(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

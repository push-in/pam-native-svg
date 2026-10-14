package dev.pam.svg

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

class SvgTestActivity : Activity() {
    lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }
}

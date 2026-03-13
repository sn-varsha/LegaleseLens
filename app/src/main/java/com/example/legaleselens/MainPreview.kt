package com.example.legaleselens

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.example.legaleselens.ui.theme.LegaleseLensTheme

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    LegaleseLensTheme {
        AndroidView(factory = { context ->
            val root = FrameLayout(context)
            LayoutInflater.from(context).inflate(R.layout.activity_main, root, true)
            root
        })
    }
}

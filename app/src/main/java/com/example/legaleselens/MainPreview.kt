package com.example.legaleselens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.legaleselens.ui.theme.LegaleseLensTheme

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    LegaleseLensTheme {
        LegaleseLensApp("")
    }
}

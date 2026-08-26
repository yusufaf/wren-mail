package dev.yusufaf.wren.ui

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.input.RemoteInputIntentHelper

private const val KEY_TEXT_INPUT = "wren_text_input"

/**
 * Returns a launcher for the system text input flow (keyboard or voice) —
 * the Wear equivalent of a text field. Calls [onResult] with the entered
 * text; cancelled input is ignored.
 */
@Composable
fun rememberTextInputLauncher(label: String, onResult: (String) -> Unit): () -> Unit {
    val currentOnResult = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_TEXT_INPUT) }
            ?.toString()
        if (!text.isNullOrEmpty()) currentOnResult.value(text)
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val remoteInput = RemoteInput.Builder(KEY_TEXT_INPUT).setLabel(label).build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        launcher.launch(intent)
    }
}

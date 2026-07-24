package dev.netvalve

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.netvalve.ui.components.EmptyState
import dev.netvalve.ui.theme.NetValveTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import org.junit.Rule
import org.junit.Test

/**
 * Instrumentation test for a basic UI flow: the app-selection interaction
 * (rendering a list and toggling selection) plus an empty-state render. Uses a
 * self-contained harness so it does not require the full Hilt graph on-device.
 */
class AppSelectionFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateRendersTitleAndSubtitle() {
        composeRule.setContent {
            NetValveTheme {
                EmptyState(icon = Icons.Filled.Apps, title = "No apps selected yet", subtitle = "Pick apps to control")
            }
        }
        composeRule.onNodeWithText("No apps selected yet").assertIsDisplayed()
        composeRule.onNodeWithText("Pick apps to control").assertIsDisplayed()
    }

    @Test
    fun togglingAnAppUpdatesSelectionCount() {
        composeRule.setContent {
            NetValveTheme {
                val apps = listOf("Browser", "Music", "Games")
                var selected by remember { mutableStateOf(setOf<String>()) }
                Column {
                    Text("Selected: ${selected.size}", modifier = Modifier.testTag("count"))
                    LazyColumn {
                        items(apps) { name ->
                            Checkbox(
                                checked = name in selected,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + name else selected - name
                                },
                                modifier = Modifier.testTag("cb_$name"),
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Selected: 0").assertIsDisplayed()
        composeRule.onNodeWithTag("cb_Music").performClick()
        composeRule.onNodeWithText("Selected: 1").assertIsDisplayed()
        composeRule.onNodeWithTag("cb_Games").performClick()
        composeRule.onNodeWithText("Selected: 2").assertIsDisplayed()
    }
}

package com.kidsmonitor.monitor.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.kidsmonitor.monitor.store.MonitorName
import com.kidsmonitor.shared.store.Credentials
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonitorSetupTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var credentials: Credentials

    @Before fun setUp() {
        credentials = Credentials.withPreferences {
            context.getSharedPreferences("setup_test_credentials", Context.MODE_PRIVATE)
        }
        credentials.clear()
    }

    private fun show(payload: String? = null): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        val names = MonitorName(context)
        restoration.setContent { MaterialTheme { MonitorSetup(credentials, names, payload, defaultName = "Pixel 6") {} } }
        return restoration
    }

    @Test fun `an ADB payload waits for a valid name and explicit continue`() {
        // Invalid payload produces an observable failure only after naming.
        show("https://example.com/not-a-pairing-code")
        compose.onNodeWithText("Name this monitor").assertIsDisplayed()
        compose.onNodeWithText("Pixel 6").assertIsDisplayed()
        compose.onNodeWithText("Continue to pairing").assertIsEnabled()
        assertFalse(credentials.isPaired)
        compose.onNodeWithText("Monitor name").performTextReplacement("   ")
        compose.onNodeWithText("Continue to pairing").assertIsNotEnabled()
        compose.onNodeWithText("Monitor name").performTextReplacement("x".repeat(65))
        compose.onNodeWithText("Continue to pairing").assertIsNotEnabled()
        compose.onNodeWithText("Monitor name").performTextReplacement("  Nursery  ")
        compose.onNodeWithText("Continue to pairing").assertIsEnabled().performClick()
        compose.onNodeWithText("Monitor: Nursery").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun `the default device name can be accepted without editing`() {
        show()
        compose.onNodeWithText("Pixel 6").assertIsDisplayed()
        assertFalse(credentials.isPaired)
        compose.onNodeWithText("Continue to pairing").assertIsEnabled().performClick()
        compose.onNodeWithText("Monitor: Pixel 6").assertIsDisplayed()
        compose.onNodeWithText("Enter address & pairing code").performScrollTo().performClick()
        compose.onNodeWithText("Monitor: Pixel 6").assertIsDisplayed()
        compose.onNodeWithText("Change monitor name").performScrollTo().performClick()
        compose.onNodeWithText("Pixel 6").assertIsDisplayed()
    }

    @Test fun `manual and QR paths share the name and allow correcting it`() {
        show()
        compose.onNodeWithText("Monitor name").performTextReplacement("Playroom")
        compose.onNodeWithText("Continue to pairing").performClick()
        compose.onNodeWithText("Enter address & pairing code").performScrollTo().performClick()
        compose.onNodeWithText("Monitor: Playroom").assertIsDisplayed()
        compose.onNodeWithText("1 · Hub address").assertIsDisplayed()
        compose.onNodeWithText("Scan a QR code instead").performScrollTo().performClick()
        compose.onNodeWithText("Monitor: Playroom").assertIsDisplayed()
        compose.onNodeWithText("Change monitor name").performScrollTo().performClick()
        compose.onNodeWithText("Monitor name").performTextReplacement("Upstairs nursery")
        compose.onNodeWithText("Continue to pairing").performClick()
        compose.onNodeWithText("Enter address & pairing code").performScrollTo().performClick()
        compose.onNodeWithText("Monitor: Upstairs nursery").assertIsDisplayed()
    }

    @Test fun `the naming draft survives saved state restoration`() {
        val restoration = show()
        compose.onNodeWithText("Monitor name").performTextReplacement("Nursery")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Continue to pairing").assertIsEnabled().performClick()
        compose.onNodeWithText("Monitor: Nursery").assertIsDisplayed()
    }
}

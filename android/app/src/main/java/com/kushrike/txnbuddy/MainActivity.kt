package com.kushrike.txnbuddy

import android.Manifest
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Set while we open a system picker/permission screen so returning from it doesn't re-lock. */
    var expectingReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (savedInstanceState == null && Store.lockEnabled) Nav.locked = true
        lifecycleScope.launch { Ai.refresh() }
        setContent { TxnTheme { Root(this) } }
    }

    override fun onResume() {
        super.onResume()
        Nav.smsGranted = SmsImport.granted(this)
        if (!Nav.locked) lifecycleScope.launch { SmsImport.autoScan(this@MainActivity) }
    }

    override fun onStop() {
        super.onStop()
        if (Store.lockEnabled && !expectingReturn && !isChangingConfigurations) Nav.locked = true
        expectingReturn = false
    }

    fun applySecureFlag(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun deviceSecure() = getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Framework BiometricPrompt (no androidx.biometric): biometrics with screen-lock fallback. */
    fun authenticate(onSuccess: () -> Unit) {
        val b = BiometricPrompt.Builder(this).setTitle("Unlock TxnBuddy").setSubtitle("Your data stays on this device")
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                // Screen lock was removed after enabling App Lock: don't lock the user out of their own data.
                if (code == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL || code == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT) onSuccess()
            }
        })
    }

    fun unlock() {
        Nav.locked = false
        lifecycleScope.launch { SmsImport.autoScan(this@MainActivity) }
    }
}

@Composable
private fun Root(activity: MainActivity) {
    LaunchedEffect(Store.lockEnabled) { activity.applySecureFlag(Store.lockEnabled) }
    Box(Modifier.fillMaxSize().background(P.bg)) {
        when {
            !Store.onboarded -> Welcome(activity)
            Nav.locked -> LockScreen(activity)
            else -> Shell(activity)
        }
    }
}

private data class TabSpec(val label: String, val on: ImageVector, val off: ImageVector)

private val TABS = listOf(
    TabSpec("Home", Icons.Rounded.Home, Icons.Outlined.Home),
    TabSpec("Activity", Icons.Rounded.ReceiptLong, Icons.Outlined.ReceiptLong),
    TabSpec("Insights", Icons.Rounded.PieChart, Icons.Outlined.PieChart),
    TabSpec("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
)

@Composable
private fun Shell(activity: MainActivity) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Nav.snack) {
        val s = Nav.snack ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(s.text, s.action, withDismissAction = false, duration = if (s.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) s.onAction()
        Nav.snack = null
    }
    BackHandler(Nav.screen != null) { Nav.screen = null }
    BackHandler(Nav.screen == null && Nav.tab != 0) { Nav.tab = 0 }

    if (Nav.screen != null) {
        Box(Modifier.fillMaxSize()) {
            when (Nav.screen) {
                "accounts" -> AccountsScreen()
                "ai" -> AiSetupScreen(activity)
                else -> CategoriesScreen()
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    } else {
        Scaffold(
            containerColor = P.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (Nav.tab <= 1) {
                    FloatingActionButton(
                        onClick = { Nav.editor = Editor() },
                        modifier = Modifier.size(60.dp),
                        shape = CircleShape,
                        containerColor = P.accent,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(6.dp, 8.dp),
                    ) { Icon(Icons.Rounded.Add, "Add transaction", Modifier.size(30.dp)) }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = P.surface, tonalElevation = 0.dp) {
                    TABS.forEachIndexed { i, t ->
                        val sel = Nav.tab == i
                        NavigationBarItem(
                            selected = sel,
                            onClick = { Nav.tab = i },
                            icon = { Icon(if (sel) t.on else t.off, null) },
                            label = { Text(t.label, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = P.accent, selectedTextColor = P.accent, indicatorColor = P.accentSoft,
                                unselectedIconColor = P.muted, unselectedTextColor = P.muted,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                AnimatedContent(Nav.tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { tab ->
                    when (tab) {
                        0 -> HomeScreen()
                        1 -> ActivityScreen()
                        2 -> InsightsScreen()
                        else -> SettingsScreen(activity)
                    }
                }
            }
        }
    }

    Nav.editor?.let { AddSheet(it) { Nav.editor = null } }
    Nav.review?.let { SmsReviewSheet(it) { Nav.review = null } }
    if (Nav.budgetDialog) BudgetDialog { Nav.budgetDialog = false }
}

@Composable
private fun LockScreen(activity: MainActivity) {
    LaunchedEffect(Unit) { activity.authenticate { activity.unlock() } }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmojiBadge("🔒", 88.dp)
        Spacer(Modifier.height(20.dp))
        Text("TxnBuddy is locked", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = P.text)
        Spacer(Modifier.height(6.dp))
        Text("Unlock to see your money.", fontSize = 15.sp, color = P.muted)
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Unlock", Modifier.width(200.dp)) { activity.authenticate { activity.unlock() } }
    }
}

@Composable
private fun Welcome(activity: MainActivity) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val finish = {
        Store.onboarded = true
        scope.launch { SmsImport.autoScan(ctx) }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        if (step == 0) {
            AppIcon(96.dp)
            Spacer(Modifier.height(20.dp))
            Text("TxnBuddy", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = P.text)
            Spacer(Modifier.height(6.dp))
            Text("Track spending without giving it away.", fontSize = 16.sp, color = P.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Feature("🔒", "Stays on your phone", "No account, no cloud, no trackers.")
                Feature("⚡️", "Reads bank SMS for you", "Parsed on-device, never uploaded.")
                Feature("📊", "Insights that make sense", "Daily, weekly, monthly, yearly.")
            }
        } else {
            EmojiBadge("💬", 96.dp)
            Spacer(Modifier.height(20.dp))
            Text("Log bank SMS automatically", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = P.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "TxnBuddy reads your bank messages on this phone to find debits and credits. " +
                    "The app has no internet access, so messages can't leave your device. Only amounts, " +
                    "merchants and dates are kept — never the message itself.",
                fontSize = 15.sp, color = P.muted, textAlign = TextAlign.Center, lineHeight = 22.sp,
            )
        }
        Spacer(Modifier.weight(1.2f))
        if (step == 0) {
            PrimaryButton("Get started") { step = 1 }
        } else {
            PrimaryButton("Allow SMS access") {
                activity.expectingReturn = true
                permission.launch(Manifest.permission.READ_SMS)
            }
            TextButton(onClick = finish, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("Not now", color = P.muted, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Feature(emoji: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EmojiBadge(emoji, 48.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.text)
            Text(body, fontSize = 14.sp, color = P.muted)
        }
    }
}

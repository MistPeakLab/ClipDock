package com.mistpeaklab.clipdock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistpeaklab.clipdock.data.Crypto
import com.mistpeaklab.clipdock.data.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 看隐藏的内容前先验证身份。
 * 已经验证过（并且没超时）就直接执行；否则弹出系统的指纹 / 锁屏密码验证，
 * 设备没设锁屏密码时用应用自己的密码。通过后再执行。
 */
object Unlock {
    private var pending: (() -> Unit)? = null

    fun run(ctx: Context, action: () -> Unit) {
        if (Vault.isUnlocked()) { action(); return }
        pending = action
        ctx.startActivity(
            Intent(ctx, UnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    internal fun success(ctx: Context) {
        Vault.markUnlocked(Prefs(ctx).unlockMinutes * 60_000L)
        val p = pending
        pending = null
        p?.invoke()
    }

    internal fun cancel() {
        pending = null
    }

    // ---------------- 应用密码 ----------------

    private const val PIN_ITERATIONS = 20_000

    fun hasPin(p: Prefs) = p.pinHash.isNotEmpty()

    /** 比较慢（故意的），不要在界面线程上调用 */
    fun hashPin(pin: String, salt: ByteArray): String =
        Base64.encodeToString(Crypto.deriveKey(pin.toCharArray(), salt, PIN_ITERATIONS).encoded, Base64.NO_WRAP)

    fun setPin(p: Prefs, pin: String) {
        val salt = Crypto.randomBytes(16)
        p.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        p.pinHash = hashPin(pin, salt)
        p.pinFails = 0
        p.pinLockedUntil = 0
    }

    fun checkPin(p: Prefs, pin: String): Boolean {
        val salt = runCatching { Base64.decode(p.pinSalt, Base64.NO_WRAP) }.getOrNull() ?: return false
        return MessageDigest.isEqual(hashPin(pin, salt).toByteArray(), p.pinHash.toByteArray())
    }
}

/** 验证身份的透明界面：只有系统的验证框（或应用密码框），验证完就关掉 */
class UnlockActivity : ComponentActivity() {
    private var cancel: CancellationSignal? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isDeviceSecure) systemPrompt()
        else setContent { AppTheme { PinScreen(Prefs(this)) { ok -> done(ok) } } }
    }

    private fun systemPrompt() {
        val b = BiometricPrompt.Builder(this)
            .setTitle(tr("验证身份", "Verify it's you"))
            .setSubtitle(tr("查看或粘贴隐藏的内容", "To view or paste hidden items"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            b.setDeviceCredentialAllowed(true)
        }
        val c = CancellationSignal()
        cancel = c
        b.build().authenticate(c, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
        })
    }

    private fun done(ok: Boolean) {
        if (finished) return
        finished = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        if (ok) Unlock.success(this) else Unlock.cancel()
    }

    override fun onDestroy() {
        cancel?.cancel()
        if (!finished) Unlock.cancel()
        super.onDestroy()
    }
}

/** 应用密码：没设过就先设一个（输两遍），设过就输入验证。连续输错会锁一会儿 */
@Composable
private fun PinScreen(prefs: Prefs, onDone: (Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val setting = remember { !Unlock.hasPin(prefs) }
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }

    fun submit() {
        if (busy) return
        if (pin.length !in 4..8) { msg = tr("请输入 4–8 位数字", "Enter 4 to 8 digits"); return }
        if (setting) {
            if (first == null) { first = pin; pin = ""; msg = null; return }
            if (first != pin) { first = null; pin = ""; msg = tr("两次输入不一样，请重新设置", "They didn't match. Start again."); return }
            busy = true
            val p = pin
            scope.launch {
                withContext(Dispatchers.Default) { Unlock.setPin(prefs, p) }
                Toast.makeText(ctx, tr("应用密码已设置", "App passcode set"), Toast.LENGTH_SHORT).show()
                onDone(true)
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < prefs.pinLockedUntil) {
            msg = tr("输错次数太多，请 %d 秒后再试", "Too many attempts. Try again in %d s", (prefs.pinLockedUntil - now) / 1000 + 1)
            return
        }
        busy = true
        val p = pin
        scope.launch {
            val ok = withContext(Dispatchers.Default) { Unlock.checkPin(prefs, p) }
            busy = false
            if (ok) { prefs.pinFails = 0; onDone(true); return@launch }
            val fails = prefs.pinFails + 1
            prefs.pinFails = fails
            pin = ""
            when {
                fails >= 10 -> { prefs.pinLockedUntil = SystemClock.elapsedRealtime() + 3_600_000L; msg = tr("已锁定 1 小时", "Locked for 1 hour") }
                fails % 5 == 0 -> { prefs.pinLockedUntil = SystemClock.elapsedRealtime() + 60_000L; msg = tr("已锁定 1 分钟", "Locked for 1 minute") }
                else -> msg = tr("密码不对", "Wrong passcode")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(24.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface).padding(24.dp),
        ) {
            Text(
                if (!setting) tr("输入应用密码", "Enter app passcode") else if (first == null) tr("设置应用密码", "Set an app passcode") else tr("再输一遍", "Enter it again"),
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (setting) tr("这台设备没有设置锁屏密码，查看隐藏的内容要用一个应用密码（4–8 位数字）。忘了它，隐藏的内容就看不到了。",
                    "This device has no screen lock, so hidden items are protected by an app passcode (4–8 digits). If you forget it, hidden items can't be viewed.")
                else tr("用来查看或粘贴隐藏的内容", "To view or paste hidden items"),
                fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                pin, { v -> pin = v.filter { it.isDigit() }.take(8); msg = null },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
            )
            msg?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onDone(false) }) { Text(tr("取消", "Cancel")) }
                Spacer(Modifier.width(8.dp))
                Button({ submit() }, enabled = !busy) { Text(tr("确定", "OK")) }
            }
        }
    }
}

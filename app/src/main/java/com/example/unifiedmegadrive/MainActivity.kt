package com.example.unifiedmegadrive

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

data class MegaAccount(val email: String, val usedBytes: Long, val totalBytes: Long)
data class UnifiedFile(val name: String, val isFolder: Boolean, val accountOwner: String)

class SecurityManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context, "secure_vault", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun isPinSet(): Boolean = prefs.getString("pin_hash", null) != null
    fun setPin(pin: String) = prefs.edit().putString("pin_hash", hash(pin)).apply()
    fun verifyPin(pin: String): Boolean = prefs.getString("pin_hash", null) == hash(pin)
    private fun hash(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

class MegaDriveManager {
    private val _accounts = MutableStateFlow<List<MegaAccount>>(emptyList())
    val accounts: StateFlow<List<MegaAccount>> = _accounts.asStateFlow()
    private val _files = MutableStateFlow<List<UnifiedFile>>(emptyList())
    val files: StateFlow<List<UnifiedFile>> = _files.asStateFlow()

    fun addAccount(email: String) {
        _accounts.value += MegaAccount(email, 2L * 1024 * 1024 * 1024, 20L * 1024 * 1024 * 1024)
        _files.value += listOf(
            UnifiedFile("Documents (${email.substringBefore("@")})", true, email),
            UnifiedFile("Backup.zip", false, email)
        )
    }
    fun getTotalGB(): Long = _accounts.value.sumOf { it.totalBytes } / (1024 * 1024 * 1024)
    fun getUsedGB(): Long = _accounts.value.sumOf { it.usedBytes } / (1024 * 1024 * 1024)
}

class MainActivity : ComponentActivity() {
    private lateinit var security: SecurityManager
    private val drive = MegaDriveManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        security = SecurityManager(this)

        setContent {
            MaterialTheme {
                var isUnlocked by remember { mutableStateOf(false) }
                if (!isUnlocked) {
                    LockScreen(
                        isSetup = !security.isPinSet(),
                        onUnlock = { pin ->
                            if (!security.isPinSet()) {
                                security.setPin(pin)
                                isUnlocked = true
                            } else if (security.verifyPin(pin)) {
                                isUnlocked = true
                            }
                        }
                    )
                } else {
                    DriveScreen(drive)
                }
            }
        }
    }
}

@Composable
fun LockScreen(isSetup: Boolean, onUnlock: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(if (isSetup) "Set App PIN Code" else "Enter PIN", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6) pin = it },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { if (pin.length >= 4) onUnlock(pin) }) {
            Text(if (isSetup) "Set PIN & Open" else "Unlock")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(drive: MegaDriveManager) {
    val accounts by drive.accounts.collectAsState()
    val files by drive.files.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Unified Drive (${accounts.size} Accounts)") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, "Add Account")
            }
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Combined Storage", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    val total = drive.getTotalGB()
                    val used = drive.getUsedGB()
                    LinearProgressIndicator(
                        progress = { if (total > 0) used.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("$used GB used of $total GB pooled total", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Merged Files", style = MaterialTheme.typography.titleLarge)
            LazyColumn {
                items(files) { file ->
                    ListItem(
                        leadingContent = { Icon(if (file.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                        headlineContent = { Text(file.name) },
                        supportingContent = { Text("From: ${file.accountOwner}") }
                    )
                }
            }
        }
        if (showDialog) {
            var email by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Add MEGA Account") },
                text = { OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }) },
                confirmButton = {
                    Button(onClick = { drive.addAccount(email); showDialog = false }) { Text("Link") }
                },
                dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

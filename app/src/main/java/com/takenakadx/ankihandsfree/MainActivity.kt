package com.takenakadx.ankihandsfree

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.takenakadx.ankihandsfree.anki.FlashCardsContract
import com.takenakadx.ankihandsfree.review.Phase
import com.takenakadx.ankihandsfree.review.ReviewViewModel
import com.takenakadx.ankihandsfree.ui.MainScreen
import com.takenakadx.ankihandsfree.ui.PermissionState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: ReviewViewModel by viewModels()
    private var permissionState by mutableStateOf(PermissionState())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()

        // 学習中は画面を消さない（ボタンをいつでも押せるように）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    val active = s.phase != Phase.IDLE && s.phase != Phase.FINISHED
                    if (active) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = viewModel,
                    permissions = permissionState,
                    onRequestPermissions = {
                        permissionLauncher.launch(arrayOf(FlashCardsContract.READ_WRITE_PERMISSION))
                    },
                    onRequestMic = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onStop() {
        super.onStop()
        // 画面が見えないところで勝手に進まないよう、アプリを離れたら学習を止める
        if (!isChangingConfigurations) viewModel.stop()
    }

    private fun refreshPermissions() {
        val next = PermissionState(
            ankiInstalled = viewModel.isAnkiDroidInstalled,
            ankiGranted = granted(FlashCardsContract.READ_WRITE_PERMISSION),
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
        )
        val becameReady = next.allReady && !permissionState.allReady
        permissionState = next
        if (becameReady) viewModel.loadDecks()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

package com.localscout.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.localscout.app.ui.LocalGroceryScoutAppRoot
import com.localscout.app.ui.connectivity.NetworkState
import com.localscout.app.ui.connectivity.NetworkStateViewModel
import com.localscout.app.ui.connectivity.NoNetworkScreen
import com.localscout.app.ui.theme.LocalGroceryScoutTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalGroceryScoutTheme {
                val networkViewModel: NetworkStateViewModel = hiltViewModel()
                val networkState by networkViewModel.networkState.collectAsState()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (networkState) {
                        is NetworkState.Online -> LocalGroceryScoutAppRoot()
                        is NetworkState.Offline -> NoNetworkScreen(
                            onRetry = { networkViewModel.recheck() }
                        )
                    }
                }
            }
        }
    }
}

package dev.goquick.kprofiles.sampleapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.goquick.kprofiles.sampleapp.config.AppConfig
import kmp_kprofiles.sample_app.composeapp.generated.resources.Res
import kmp_kprofiles.sample_app.composeapp.generated.resources.build_type_label
import kmp_kprofiles.sample_app.composeapp.generated.resources.logo
import kmp_kprofiles.sample_app.composeapp.generated.resources.platform_label
import kmp_kprofiles.sample_app.composeapp.generated.resources.profile_cta
import kmp_kprofiles.sample_app.composeapp.generated.resources.profile_hint
import kmp_kprofiles.sample_app.composeapp.generated.resources.profile_message
import kmp_kprofiles.sample_app.composeapp.generated.resources.profile_name
import kmp_kprofiles.sample_app.composeapp.generated.resources.profile_title
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalResourceApi::class)
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val title = stringResource(Res.string.profile_title)
            val message = stringResource(Res.string.profile_message)
            val cta = stringResource(Res.string.profile_cta)
            val hint = stringResource(Res.string.profile_hint)
            val profileName = stringResource(Res.string.profile_name)
            val accentColor = AppConfig.mainColor
            val featureStatus = if (AppConfig.featureX) "enabled" else "disabled"
            val platformLabel = stringResource(Res.string.platform_label)
            val buildTypeLabel = stringResource(Res.string.build_type_label)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(Res.drawable.logo),
                    contentDescription = title,
                    modifier = Modifier.size(144.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = profileName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = accentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = cta,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = accentColor
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "API: ${AppConfig.apiBaseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Retries: ${AppConfig.retryCount}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Timeout: ${AppConfig.timeoutSec}s",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Feature X: $featureStatus",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Prop secret: ${AppConfig.propertySecret}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Platform: $platformLabel",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Build: $buildTypeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

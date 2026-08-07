package com.ogesture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.ogesture.R

/**
 * Prominent disclosure shown before the user is sent to Accessibility settings, as required by
 * Play's accessibility-service policy: what the service is used for, what it can reach, and what
 * happens to the user's data. Nothing is granted from here — [onContinue] only opens Settings.
 */
@Composable
fun AccessibilityConsentDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(imageVector = Icons.Outlined.Info, contentDescription = null)
        },
        title = { Text(stringResource(R.string.a11y_consent_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DisclosureSection(
                    label = stringResource(R.string.a11y_consent_why_label),
                    body = stringResource(R.string.a11y_consent_why_body),
                )
                DisclosureSection(
                    label = stringResource(R.string.a11y_consent_data_label),
                    body = stringResource(R.string.a11y_consent_data_body),
                )
                DisclosureSection(
                    label = stringResource(R.string.a11y_consent_control_label),
                    body = stringResource(R.string.a11y_consent_control_body),
                )
                Text(
                    text = buildAnnotatedString {
                        withLink(LinkAnnotation.Url(PRIVACY_POLICY_URL, privacyLinkStyles())) {
                            append(stringResource(R.string.a11y_consent_privacy_policy))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.a11y_consent_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.a11y_consent_cancel))
            }
        },
    )
}

@Composable
private fun DisclosureSection(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun privacyLinkStyles() = TextLinkStyles(
    style = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    ),
)

const val PRIVACY_POLICY_URL = "https://github.com/tanujnotes/Ogesture/blob/main/PRIVACY.md"

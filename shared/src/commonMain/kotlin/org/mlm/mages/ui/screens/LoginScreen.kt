package org.mlm.mages.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.matrix.PasswordLoginKind
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.LoginViewModel

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onSso: () -> Unit,
    onOauth: () -> Unit,
    isAddingAccount: Boolean = false
) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }

    val details = state.loginDetails
    val oauthAvailable = details?.supportsOauth == true
    val ssoAvailable = details?.supportsSso == true
    val passwordAvailable = details?.supportsPassword == true
    val serverKnown = details != null

    val identifierLabel = when (state.passwordLoginKind) {
        PasswordLoginKind.Username -> stringResource(Res.string.username)
        PasswordLoginKind.Email -> "Email"
        PasswordLoginKind.Phone -> "Phone number"
    }

    val identifierPlaceholder = when (state.passwordLoginKind) {
        PasswordLoginKind.Username -> stringResource(Res.string.username_placeholder)
        PasswordLoginKind.Email -> "alice@example.org"
        PasswordLoginKind.Phone -> "5151634567"
    }

    val identifierKeyboardType = when (state.passwordLoginKind) {
        PasswordLoginKind.Username -> KeyboardType.Text
        PasswordLoginKind.Email -> KeyboardType.Email
        PasswordLoginKind.Phone -> KeyboardType.Phone
    }

    val canSubmitPassword = when (state.passwordLoginKind) {
        PasswordLoginKind.Phone ->
            !state.isBusy &&
                state.user.isNotBlank() &&
                state.pass.isNotBlank() &&
                state.phoneCountry.trim().length == 2

        else ->
            !state.isBusy &&
                state.user.isNotBlank() &&
                state.pass.isNotBlank()
    }

    // The best auth method becomes filled
    val primaryAuth = when {
        oauthAvailable -> "oauth"
        ssoAvailable -> "sso"
        else -> "password"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerLowest,
                        MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            )
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(100.dp)
            ) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Title
            AnimatedContent(
                targetState = Triple(state.isBusy, state.ssoInProgress, state.oauthInProgress),
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "title"
            ) { (isBusy, ssoInProgress, oauthInProgress) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            ssoInProgress -> stringResource(Res.string.waiting_for_sso)
                            oauthInProgress -> stringResource(Res.string.waiting_for_sso) // reuse or add new string
                            isBusy -> stringResource(Res.string.connecting)
                            isAddingAccount -> stringResource(Res.string.add_account)
                            else -> stringResource(Res.string.welcome_to_mages)
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            ssoInProgress || oauthInProgress -> stringResource(Res.string.complete_login_in_browser)
                            isBusy -> stringResource(Res.string.please_wait)
                            else -> stringResource(Res.string.sign_in_to_your_matrix_account)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // Main card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 300f)),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Homeserver field (always visible, compact)
                    OutlinedTextField(
                        value = state.homeserver,
                        onValueChange = viewModel::setHomeserver,
                        label = { Text(stringResource(Res.string.homeserver)) },
                        placeholder = { Text(stringResource(Res.string.homeserver_placeholder)) },
                        leadingIcon = { Icon(Icons.Default.Home, null) },
                        trailingIcon = {
                            if (state.isCheckingServer) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                )
                            } else if (serverKnown && (passwordAvailable || oauthAvailable || ssoAvailable)) {
                                Icon(
                                    Icons.Default.CheckCircle, null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else if (serverKnown && !passwordAvailable && !oauthAvailable && !ssoAvailable) {
                                Icon(
                                    Icons.Default.Warning, "Homeserver has no login methods or might not exist",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isBusy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLeadingIconColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    // OAuth button
                    AnimatedVisibility(
                        visible = oauthAvailable || state.oauthInProgress,
                        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                    ) {
                        if (state.oauthInProgress) {
                            OutlinedButton(
                                onClick = { viewModel.cancelOauth() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Close, null)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(stringResource(Res.string.cancel_oauth))
                            }
                        } else if (primaryAuth == "oauth") {
                            Button(
                                onClick = onOauth,
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            ) {
                                Icon(Icons.Default.Security, null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(Res.string.continue_with_oauth),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = onOauth,
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Security, null)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(stringResource(Res.string.continue_with_oauth))
                            }
                        }
                    }

                    // SSO button
                    if (currentPlatform != SettingPlatform.WEB) {
                        AnimatedVisibility(
                            visible = ssoAvailable || state.ssoInProgress,
                            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                        ) {
                            if (state.ssoInProgress) {
                                OutlinedButton(
                                    onClick = { viewModel.cancelSso() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.Close, null)
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(stringResource(Res.string.cancel_sso))
                                }
                            } else if (primaryAuth == "sso") {
                                Button(
                                    onClick = onSso,
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(Res.string.continue_with_sso),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onSso,
                                    enabled = !state.isBusy && !state.oauthInProgress,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, null)
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(stringResource(Res.string.continue_with_sso))
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = passwordAvailable || !serverKnown,
                        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Show the toggle only when there's a better method above
                            if (primaryAuth != "password" && serverKnown) {
                                TextButton(
                                    onClick = { viewModel.togglePasswordLogin() },
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        HorizontalDivider(modifier = Modifier.weight(1f))
                                        Text(
                                            text = stringResource(Res.string.or_use_password),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                        HorizontalDivider(modifier = Modifier.weight(1f))
                                    }
                                }
                            }

                            // Shown when it's the primary method or user expanded
                            AnimatedVisibility(
                                visible = state.showPasswordLogin || primaryAuth == "password",
                                enter = fadeIn(tween(300)) + expandVertically(
                                    spring(dampingRatio = 0.8f, stiffness = 300f)
                                ),
                                exit = fadeOut(tween(200)) + shrinkVertically(
                                    spring(dampingRatio = 0.8f, stiffness = 300f)
                                )
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SingleChoiceSegmentedButtonRow(
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        PasswordLoginKind.entries.forEachIndexed { index, mode ->
                                            SegmentedButton(
                                                selected = state.passwordLoginKind == mode,
                                                onClick = { viewModel.setPasswordLoginKind(mode) },
                                                enabled = !state.isBusy,
                                                shape = SegmentedButtonDefaults.itemShape(
                                                    index = index,
                                                    count = PasswordLoginKind.entries.size
                                                ),
                                            ) {
                                                Text(
                                                    when (mode) {
                                                        PasswordLoginKind.Username -> "Username"
                                                        PasswordLoginKind.Email -> "Email"
                                                        PasswordLoginKind.Phone -> "Phone"
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    if (state.passwordLoginKind == PasswordLoginKind.Phone) {
                                        OutlinedTextField(
                                            value = state.phoneCountry,
                                            onValueChange = viewModel::setPhoneCountry,
                                            label = { Text("Country code") },
                                            placeholder = { Text("US") },
                                            leadingIcon = { Icon(Icons.Default.Flag, null) },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !state.isBusy,
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Text,
                                                imeAction = ImeAction.Next,
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                            )
                                        )
                                    }

                                    if (state.passwordLoginKind != PasswordLoginKind.Username) {
                                        Text(
                                            text = "Not normally configured, available for special cases.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    OutlinedTextField(
                                        value = state.user,
                                        onValueChange = viewModel::setUser,
                                        label = { Text(identifierLabel) },
                                        placeholder = { Text(identifierPlaceholder) },
                                        leadingIcon = {
                                            Icon(
                                                when (state.passwordLoginKind) {
                                                    PasswordLoginKind.Username -> Icons.Default.Person
                                                    PasswordLoginKind.Email -> Icons.Default.Email
                                                    PasswordLoginKind.Phone -> Icons.Default.Phone
                                                },
                                                null
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.isBusy,
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = identifierKeyboardType,
                                            imeAction = ImeAction.Next
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            focusedLeadingIconColor = MaterialTheme.colorScheme.primary
                                        )
                                    )

                                    OutlinedTextField(
                                        value = state.pass,
                                        onValueChange = viewModel::setPass,
                                        label = { Text(stringResource(Res.string.password)) },
                                        placeholder = { Text(stringResource(Res.string.enter_your_password)) },
                                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                                        trailingIcon = {
                                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                                Icon(
                                                    if (passwordVisible) Icons.Default.VisibilityOff
                                                    else Icons.Default.Visibility,
                                                    null
                                                )
                                            }
                                        },
                                        visualTransformation = if (passwordVisible)
                                            VisualTransformation.None
                                        else
                                            PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.isBusy,
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                focusManager.clearFocus()
                                                if (canSubmitPassword) {
                                                    viewModel.submit()
                                                }
                                            }
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            focusedLeadingIconColor = MaterialTheme.colorScheme.primary
                                        )
                                    )

                                    Button(
                                        onClick = viewModel::submit,
                                        enabled = canSubmitPassword,
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                        shape = MaterialTheme.shapes.large
                                    ) {
                                        if (state.isBusy && !state.ssoInProgress && !state.oauthInProgress) {
                                            CircularWavyProgressIndicator(
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Login, null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                stringResource(Res.string.sign_in),
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Error message
                    AnimatedVisibility(
                        visible = !state.error.isNullOrBlank(),
                        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(Spacing.md),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Error, null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                SelectionContainer {
                                    Text(
                                        state.error ?: "",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
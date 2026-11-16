package com.mackseoul.vcardnative.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.PublicKeyAsset
import com.mackseoul.vcardnative.domain.keys.PublicKeyStatus

@Composable
internal fun PublicKeyMetadata(asset: PublicKeyAsset, status: PublicKeyStatus?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.public_key_fingerprint, asset.keyVersion.toString()),
            style = MaterialTheme.typography.bodyLarge)
        Text(asset.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodyMedium)
        Body(R.string.public_key_identity_warning)
        status?.explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

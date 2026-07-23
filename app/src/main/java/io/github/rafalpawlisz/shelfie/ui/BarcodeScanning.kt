package io.github.rafalpawlisz.shelfie.ui

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Opens the Google Play Services code scanner and reports the raw value of a
 * successful scan. Restricted to grocery 1D formats. Cancel and failure are
 * silent — the user simply returns to the form. No CAMERA permission needed;
 * scanning runs in Play Services.
 */
fun scanBarcode(context: Context, onResult: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
        )
        .build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let(onResult) }
}

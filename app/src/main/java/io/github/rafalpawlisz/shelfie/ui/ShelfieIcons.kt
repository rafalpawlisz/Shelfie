package io.github.rafalpawlisz.shelfie.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

// Material's "remove" (minus) icon is only shipped in material-icons-extended
// (~11 MB); this is its exact path geometry, so we skip that dependency.
internal val RemoveIcon: ImageVector = materialIcon(name = "Filled.Remove") {
    materialPath {
        moveTo(19.0f, 13.0f)
        horizontalLineTo(5.0f)
        verticalLineTo(11.0f)
        horizontalLineTo(19.0f)
        close()
    }
}

// A simple barcode glyph (icons-core has no scanner/barcode icon). Four
// vertical bars of varying width on the 24dp grid.
internal val BarcodeIcon: ImageVector = materialIcon(name = "Filled.Barcode") {
    materialPath { moveTo(4.0f, 5.0f); horizontalLineTo(6.0f); verticalLineTo(19.0f); horizontalLineTo(4.0f); close() }
    materialPath { moveTo(8.0f, 5.0f); horizontalLineTo(9.0f); verticalLineTo(19.0f); horizontalLineTo(8.0f); close() }
    materialPath { moveTo(11.0f, 5.0f); horizontalLineTo(14.0f); verticalLineTo(19.0f); horizontalLineTo(11.0f); close() }
    materialPath { moveTo(16.0f, 5.0f); horizontalLineTo(17.0f); verticalLineTo(19.0f); horizontalLineTo(16.0f); close() }
    materialPath { moveTo(19.0f, 5.0f); horizontalLineTo(20.0f); verticalLineTo(19.0f); horizontalLineTo(19.0f); close() }
}

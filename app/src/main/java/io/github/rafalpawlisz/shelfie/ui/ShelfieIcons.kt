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

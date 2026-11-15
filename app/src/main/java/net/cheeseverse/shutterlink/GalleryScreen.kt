package net.cheeseverse.shutterlink

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

// Camera thumbnails (DT*) are a fixed size with a 4:3 aspect ratio, so every cell has a
// predictable height and the grid can reserve room for all pictures before they are loaded.
private const val THUMBNAIL_ASPECT_RATIO = 4f / 3f
private val THUMBNAIL_MIN_WIDTH = 128.dp
private val THUMBNAIL_SHAPE = RoundedCornerShape(8.dp)

/**
 * Scrollable gallery of all pictures on the camera.
 *
 * [totalPictures] is the camera-reported picture count; one cell is reserved for each so the
 * scroll range matches the whole card. Index 0 is the newest picture. [thumbnails] holds the
 * already-cached thumbnail file names (newest first); cells beyond it show a placeholder until
 * loading is implemented.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PicList(
    totalPictures: Int,
    thumbnails: List<String>,
    ctx: Context,
    snackbarHostState: SnackbarHostState,
    downloading: (Boolean) -> Unit,
    downloadProgress: (Float) -> Unit,
    imagePreviewVisible: (Boolean) -> Unit,
    imageToPreview: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        columns = GridCells.Adaptive(minSize = THUMBNAIL_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        items(count = maxOf(totalPictures, thumbnails.size)) { index ->
            val thumbnail = thumbnails.getOrNull(index)
            val cellModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(THUMBNAIL_ASPECT_RATIO)
                .clip(THUMBNAIL_SHAPE)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, THUMBNAIL_SHAPE)

            if (thumbnail == null) {
                // Not loaded yet (preview loading comes later): empty placeholder cell.
                Box(modifier = cellModifier.background(MaterialTheme.colorScheme.surfaceVariant))
            } else {
                AsyncImage(
                    model = File(ctx.cacheDir, thumbnail),
                    contentDescription = null,
                    modifier = cellModifier.combinedClickable(
                        onLongClick = {
                            GlobalScope.launch {
                                downloadThumbnail(ctx, "DL" + thumbnail.substring(2), imageToPreview)
                                imagePreviewVisible(true)
                            }
                        },
                        onClick = {
                            GlobalScope.launch {
                                val imageUri = downloadHQImage(
                                    thumbnail,
                                    ctx,
                                    { visible -> downloading(visible) },
                                    downloadProgress
                                )
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Content Downloaded",
                                        actionLabel = "Open",
                                        duration = SnackbarDuration.Short,
                                        withDismissAction = true
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        val intent = Intent(Intent.ACTION_VIEW)
                                        intent.setDataAndType(imageUri, "image/*")
                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        if (intent.resolveActivity(ctx.packageManager) != null) {
                                            startActivity(ctx, intent, null)
                                        }
                                    }
                                }
                            }
                        }
                    ),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

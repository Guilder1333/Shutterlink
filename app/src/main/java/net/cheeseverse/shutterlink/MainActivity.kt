package net.cheeseverse.shutterlink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat.startActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.cheeseverse.shutterlink.ui.theme.ShutterlinkTheme
import java.io.File
import java.util.UUID


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            initBox(this);
        }
        //initialize the uuid
        val sharedPrefs = this.getPreferences(Context.MODE_PRIVATE)
        deviceUUID = sharedPrefs.getString("persistUUID", deviceUUID + UUID.randomUUID().toString().substring(24).uppercase())!!
        if(!sharedPrefs.contains("persistUUID")) {
            with (sharedPrefs.edit()) {
                putString("persistUUID", deviceUUID)
                commit()
            }
        }
        startActivity(this, Intent(Settings.Panel.ACTION_WIFI), null)

    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if(cameraMode != "" && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            GlobalScope.launch { captureImage() }
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        applicationContext.cacheDir.deleteRecursively()
    }
}

//UI Below
@Composable
fun initBox(ctx: Context) {
    var thumbnails by remember {mutableStateOf(List<String>(0){"empty"})};
    var olderNewerButtonsVisible by remember { mutableStateOf(false)}
    var connectionProgressVisible by remember { mutableStateOf(false) }
    var connectionProgressText by remember { mutableStateOf("Searching for Camera")}
    var downloading by remember { mutableStateOf(false)}
    var downloadProgress by remember { mutableStateOf(0.0f)}
    var connectionMethodSelected by remember { mutableStateOf(false)}
    var connectionToSelect by remember { mutableStateOf(1)}
    var imagePreviewVisible by remember { mutableStateOf(false)}
    var imageToPreview by remember { mutableStateOf("")}

    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.zIndex(10.0f))
        },
    ) { contentPadding ->
        if (downloading) {
            Dialog(onDismissRequest = { /*This dialog can't be dismissed*/ }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(verticalArrangement = Arrangement.SpaceAround) {
                        Row(modifier = Modifier.height(30.dp)) {}
                        Row {
                            Text(
                                text = "Downloading",
                                fontSize = 4.em,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .wrapContentSize(Alignment.Center),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Row(
                            modifier = Modifier
                                .height(40.dp)
                                .fillMaxWidth()
                        ) {}
                        Row {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        if (imagePreviewVisible) {
            Dialog(onDismissRequest = { imagePreviewVisible = false}) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    AsyncImage(model = File(ctx.cacheDir, imageToPreview), contentDescription = "Image Preview")
                }
            }
        }

        if (!connectionMethodSelected) {
            Dialog(onDismissRequest = { /*This dialog can't be dismissed*/ }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.5f)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }) {
                                    connectionToSelect = 1
                                }
                                .background(if (connectionToSelect == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight(0.6f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.downloadicon),
                                    contentDescription = "Download Icon",
                                    tint = if (connectionToSelect == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                )
                            }
                            Row {
                                Box(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .fillMaxWidth()
                                ) {
                                    Column {
                                        Row {
                                            Text(
                                                "Download Images From Camera",
                                                textAlign = TextAlign.Center,
                                                fontSize = 4.em,
                                                color = if (connectionToSelect == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            RadioButton(
                                                selected = (connectionToSelect == 1),
                                                onClick = { connectionToSelect = 1 }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Column(modifier = Modifier
                            .fillMaxHeight()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }) {
                                connectionToSelect = 2
                            }
                            .background(if (connectionToSelect == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight(0.6f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.camera_transfer),
                                    contentDescription = "Download Icon",
                                    tint = if (connectionToSelect == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                )
                            }
                            Row {
                                Box(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .fillMaxWidth()
                                ) {
                                    Column {
                                        Row {
                                            Text(
                                                "Create Background Link",
                                                textAlign = TextAlign.Center,
                                                fontSize = 4.em,
                                                color = if (connectionToSelect == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            RadioButton(
                                                selected = (connectionToSelect == 2),
                                                onClick = { connectionToSelect = 2 })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(450.dp)
                ) {
                    Button(onClick = {
                        if (connectionToSelect == 1) {
                            if (requestedPages < 0) {
                                GlobalScope.launch { //connect, then request the newest page so we don't have a blank page
                                    getAvailablePictures()
                                    try {
                                        enumeratePicturesOnCamera(
                                            ctx,
                                            curIndex + imagesPerRequest,
                                            { newThumbnailList -> thumbnails += newThumbnailList })
                                    } catch (e: Exception) {
                                        println("Unable to request more pictures")
                                    }

                                }
                                olderNewerButtonsVisible = true

                            }
                        } else if (connectionToSelect == 2) {
                            GlobalScope.launch {
                                connectionProgressVisible = true
                                registerAsReceiver(
                                    ctx,
                                    { txt: String -> connectionProgressText = txt },
                                    { dwn -> downloading = dwn },
                                    { dp -> downloadProgress = dp })
                                delay(500)
                                connectionProgressText = "Connection Successful"
                                delay(500)
                            }
                        }

                        connectionMethodSelected =
                            true //runs regardless of the selected connection method
                    }) {
                        Text("Connect")
                    }
                }
            }
        }

        ShutterlinkTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.background),
                verticalArrangement = Arrangement.Top
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    PicList(
                        thumbnails,
                        ctx,
                        snackbarHostState,
                        { visible -> downloading = visible },
                        { dp -> downloadProgress = dp },
                        {vis -> imagePreviewVisible = vis},
                        {itm -> imageToPreview = itm});
                } //PicList
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            if (connectionProgressVisible) {
                                Text(
                                    connectionProgressText,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            if (olderNewerButtonsVisible) {
                                OutlinedButton(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(50.dp),
                                    onClick = {
                                        while (requestedPages < 0) {
                                        } //gotta wait for init, might not have happened yet
                                        if (requestedPages >= 0) {
                                            GlobalScope.launch {
                                                thumbnails = List<String>(0) { "" }
                                                try {
                                                    enumeratePicturesOnCamera(
                                                        ctx,
                                                        curIndex + imagesPerRequest,
                                                        { newThumbnailList -> thumbnails += newThumbnailList })
                                                } catch (e: Exception) {
                                                    println("Unable to request more pictures")
                                                }
                                            }
                                            requestedPages += 1
                                        }
                                    }) {
                                    Text("Newer", color = MaterialTheme.colorScheme.onBackground)
                                }

//                                OutlinedButton(
//                                    modifier = Modifier
//                                        .width(100.dp)
//                                        .height(50.dp),
//                                    onClick = {
//                                            GlobalScope.launch {
//                                                try {
//                                                    captureImage()
//                                                } catch (e: Exception) {
//                                                    println("Unable to capture image")
//                                                }
//                                            }
//                                    }) {
//                                    Text("Capture", color = MaterialTheme.colorScheme.onBackground)
//                                }

                                OutlinedButton(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(50.dp),
                                    onClick = {
                                        while (requestedPages < 0) {
                                        } //gotta wait for init, might not have happened yet
                                        if (requestedPages >= 0) {
                                            GlobalScope.launch {
                                                thumbnails = List<String>(0) { "" }
                                                try {
                                                    enumeratePicturesOnCamera(
                                                        ctx,
                                                        curIndex - imagesPerRequest,
                                                        { newThumbnailList -> thumbnails += newThumbnailList })
                                                } catch (e: Exception) {
                                                    println("Unable to request more pictures")
                                                }
                                            }
                                            requestedPages += 1
                                        }
                                    }) {
                                    Text("Older", color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp))
            }

        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PicList(imagePaths: List<String>, ctx: Context, snackbarHostState: SnackbarHostState, downloading: (Boolean) -> Unit, downloadProgress: (Float) -> Unit, imagePreviewVisible: (Boolean) -> Unit, imageToPreview: (String) -> Unit) {
    val scope = rememberCoroutineScope()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        items(imagePaths.size) { index ->
            AsyncImage(
                model = File(ctx.cacheDir, imagePaths[index]),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(
                            topStart = 8.dp,
                            topEnd = 8.dp,
                            bottomStart = 8.dp,
                            bottomEnd = 8.dp
                        )
                    )
                    .wrapContentHeight()
                    .combinedClickable(
                        onLongClick = {
                            GlobalScope.launch {
                                downloadThumbnail(ctx, "DL" + imagePaths[index].substring(2), imageToPreview)
                                imagePreviewVisible(true)
                            }
//                            imageToPreview(imagePaths[index])
                        },
                        onClick = {
                            GlobalScope.launch {
                                val imageUri = downloadHQImage(
                                    imagePaths[index],
                                    ctx,
                                    { visible -> downloading(visible) },
                                    downloadProgress
                                )
                                scope.launch {
                                    val result = snackbarHostState
                                        .showSnackbar(
                                            message = "Content Downloaded",
                                            actionLabel = "Open",
                                            duration = SnackbarDuration.Short,
                                            withDismissAction = true
                                        )
                                    when (result) {
                                        SnackbarResult.ActionPerformed -> {
                                            val intent = Intent(Intent.ACTION_VIEW)
                                            intent.setDataAndType(imageUri, "image/*")
                                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                            if (intent.resolveActivity(ctx.packageManager) != null) {
                                                startActivity(ctx, intent, null)
                                            }
                                        }

                                        SnackbarResult.Dismissed -> {
                                        }
                                    }
                                }
                            }
                        }
                    )
                    .clip(
                        shape = RoundedCornerShape(
                            topStart = 8.dp,
                            topEnd = 8.dp,
                            bottomStart = 8.dp,
                            bottomEnd = 8.dp
                        )
                    ),
                contentScale = ContentScale.Crop
            )
        }
    }
//    })
}
package net.cheeseverse.shutterlink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.CancellationException

var curIndex = 0;
var numPicsOnCamera = 0;
var requestedPages = -1;
var picReceiverConnectionStage = 0;
var deviceIPAddress = ""
var cameraIPAddress = ""
val imagesPerRequest = 21
var deviceUUID = "4D454930-0100-1000-8001-"
var cameraMode = ""
var cameraName = "" //camera model, used as the download folder name

/** Download folder under DCIM: the camera model name, or "Lumix" if it couldn't be read. */
fun downloadFolder(): String = cameraName.ifEmpty { "Lumix" }

/** Reads the camera model (e.g. DMC-G85) from the capability info; leaves the name empty on failure. */
fun readCameraName() {
    try {
        val text = String(readBytesWithTimeout("http://$cameraIPAddress:80/cam.cgi?mode=getinfo&type=capability"))
        println("Capability info: $text")
        for (tag in listOf("model_name", "modelname", "productname", "product_name")) {
            val m = Regex("<$tag>([^<]+)</$tag>", RegexOption.IGNORE_CASE).find(text) ?: continue
            val name = m.groupValues[1].trim().replace(Regex("[^A-Za-z0-9_\\- ]"), "").trim()
            if (name.isNotEmpty()) { cameraName = name; return }
        }
    } catch (e: Exception) {
        println("Unable to read camera name: $e")
    }
}

suspend fun doKeepAlive() {
    while(true) {
        try {
            val url = URL("http://$cameraIPAddress:80/cam.cgi?mode=getstate")
//            val con = url.openConnection() as HttpURLConnection
//            delay(100);
//            if (con.responseCode != 200) {
//                println("Keepalive Failed with Message: " + con.responseMessage)
//            }
            val text = url.readText()
            cameraMode = text.substring(text.indexOf("<cammode>") + 9, text.indexOf("</cammode>"))
            println(cameraMode)
//            con.disconnect();
            delay(500)
        } catch(e: Exception) {
            println("Keepalive failed catastrophically: $e")
            requestedPages = -1
            picReceiverConnectionStage = 0
            cameraIPAddress = ""
            deviceIPAddress = ""
            break
        }
    }
}

suspend fun getCameraIntoPlaybackMode() {
    GlobalScope.launch { ackDiscoveryPackets() } //Discover the camera
    while(cameraIPAddress == "") {}
    println("Pairing and connecting")
    withContext(Dispatchers.IO) {
        var url = URL("http://$cameraIPAddress:80/cam.cgi?mode=accctrl&type=req_acc&value=0&value2=Shutterlink")
        var con = url.openConnection() as HttpURLConnection
        delay(250);
        println("First Request: " + con.responseMessage)
        con.disconnect();

        url = URL("http://$cameraIPAddress:80/cam.cgi?mode=getsetting&type=pa")
        con = url.openConnection() as HttpURLConnection
        delay(250);
        println("Second Request: " + con.responseMessage)
        con.disconnect();

        url = URL("http://$cameraIPAddress:80/cam.cgi?mode=camcmd&value=playmode")
        con = url.openConnection() as HttpURLConnection
        delay(250);
        println("Third Request: " + con.responseMessage)
        con.disconnect();
    }
    GlobalScope.launch { doKeepAlive() }
}

suspend fun getAvailablePictures() {
    getCameraIntoPlaybackMode() //first get the camera ready
    withContext(Dispatchers.IO) {
        readCameraName()
        println("Requesting Available Images")
        val url = URL("http://$cameraIPAddress:60606/Server0/CDS_control")
        val con = url.openConnection() as HttpURLConnection
        con.requestMethod = "POST"  // optional default is GET
        con.setRequestProperty("Content-Type", "text/xml");
        con.setRequestProperty(
            "SOAPACTION",
            "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"
        );
        con.doOutput = true;
        con.outputStream.use { os ->
            val body =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\" xmlns:pana=\"urn:schemas-panasonic-com:pana\"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>15</RequestedCount><SortCriteria></SortCriteria><pana:X_FromCP>Shutterlink</pana:X_FromCP></u:Browse></s:Body></s:Envelope>".toByteArray(
                    Charsets.UTF_8
                )
            os.write(body, 0, body.size)
        }
        val data = con.inputStream.bufferedReader().readText();
        numPicsOnCamera = data.substring(data.indexOf("<TotalMatches>") + 14, data.indexOf("</TotalMatches>")).toInt(); //there are x number of pictures on the camera
        curIndex = numPicsOnCamera;
        println("There are $numPicsOnCamera available on the camera")
        requestedPages = 0;
    }
}

const val CAMERA_TIMEOUT_MS = 8000
const val THUMBNAIL_PAUSE_MS = 50L

/** Like URL.readBytes() but a stalled camera raises an exception instead of blocking forever. */
fun readBytesWithTimeout(address: String): ByteArray {
    val con = URL(address).openConnection() as HttpURLConnection
    con.connectTimeout = CAMERA_TIMEOUT_MS
    con.readTimeout = CAMERA_TIMEOUT_MS
    try {
        return con.inputStream.use { it.readBytes() }
    } finally {
        con.disconnect()
    }
}

/**
 * Loads one page of [imagesPerRequest] thumbnails. Page 0 holds the newest pictures; grid index
 * 0 is the newest picture. Thumbnails already in the app cache are not downloaded again.
 * [onLoaded] receives the grid index and cached file name of each thumbnail.
 */
suspend fun loadThumbnailPage(context: Context, page: Int, onLoaded: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
    val firstGridIndex = page * imagesPerRequest
    val count = minOf(imagesPerRequest, numPicsOnCamera - firstGridIndex)
    if (count <= 0) return@withContext
    val startingIndex = numPicsOnCamera - firstGridIndex - count //camera index is oldest first
    println("Requesting page $page: start $startingIndex, count $count")
    val url = URL("http://$cameraIPAddress:60606/Server0/CDS_control")
    val con = url.openConnection() as HttpURLConnection
    con.connectTimeout = CAMERA_TIMEOUT_MS
    con.readTimeout = CAMERA_TIMEOUT_MS
    try {
        con.requestMethod = "POST"
        con.setRequestProperty("Content-Type", "text/xml");
        con.setRequestProperty("SOAPACTION", "urn:schemas-upnp-org:service:ContentDirectory:1#Browse");
        con.doOutput = true;
        con.outputStream.use { os ->
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\" xmlns:pana=\"urn:schemas-panasonic-com:pana\"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$startingIndex</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria><pana:X_FromCP>Shutterlink</pana:X_FromCP></u:Browse></s:Body></s:Envelope>".toByteArray(Charsets.UTF_8)
            os.write(body, 0, body.size)
        }
        val data = con.inputStream.bufferedReader().readText();
        val picURLs = Regex("http://\\d{2,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+/\\w+\\.JPG").findAll(data).map { m -> m.value }.filter { it.contains("/DT") }.toList()
        //the camera lists oldest first; reverse so grid index 0 is the newest
        picURLs.reversed().forEachIndexed { i, pic ->
            val filename = pic.substring(pic.lastIndexOf("/") + 1)
            val cached = File(context.cacheDir, filename)
            if (!cached.exists()) {
                val tempFile = File.createTempFile(filename, null, context.cacheDir);
                tempFile.appendBytes(readBytesWithTimeout(pic))
                tempFile.renameTo(cached)
                delay(THUMBNAIL_PAUSE_MS) //go easy on the camera
            }
            onLoaded(firstGridIndex + i, filename)
        }
    } finally {
        con.disconnect()
    }
}

suspend fun downloadHQImage(path: String, ctx: Context, downloadDialogVisible: (Boolean) -> Unit, downloadProgress: (Float) -> Unit): Uri {
    downloadDialogVisible(true)
    val fileName = "DO" + path.substring(2);
    println(fileName);
    var url = URL("http://$cameraIPAddress:50001/$fileName");
//    var con = url.openConnection() as HttpURLConnection
//    val fileSize = con.getHeaderField("X-FILE_SIZE").toInt()
    var imageData = "".toByteArray()
    var isVideo = false
    try {
        imageData = url.readBytes()
    } catch(e: FileNotFoundException) {
        url = URL("http://$cameraIPAddress:50001/${fileName.substring(0, fileName.length - 3) + "mp4"}");
        imageData = url.readBytes()
        isVideo = true
    }
    val resolver = ctx.contentResolver;
    val info = ContentValues();
    // Fill in the name/path/type BEFORE inserting, otherwise MediaStore picks its own name and folder
    if(isVideo) {
        info.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName.substring(0, fileName.length - 3) + "mp4");
        info.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
    } else {
        info.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        info.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
    }
    info.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + File.separator + downloadFolder());
    val imageUri = resolver.insert(
        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        info
    );
    val os = resolver.openOutputStream(imageUri!!);
    if (os != null) {
        os.write(imageData)
        os.close()
    }
    downloadDialogVisible(false)
    return imageUri
}

suspend fun constantlyAdvertiseSSDP(socket: MulticastSocket) {
    while(picReceiverConnectionStage < 4) {
        val bytes = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                "\r\n").toByteArray(Charsets.UTF_8)
        val advPacket =
            DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900)
        socket.send(advPacket)

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: uuid:$deviceUUID\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID\r\n" +
                    "\r\n", socket
        )

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: upnp:rootdevice\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID::upnp:rootdevice\r\n" +
                    "\r\n", socket
        )

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID::urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                    "\r\n", socket
        )

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID::urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
                    "\r\n", socket
        )

        advertiseSSDPPacket(
            "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
                    "NT: urn:schemas-upnp-org:service:ConnectionManager:1\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "USN: uuid:$deviceUUID::urn:schemas-upnp-org:service:ConnectionManager:1\r\n" +
                    "\r\n", socket
        )
        delay(4000)
    }
}

suspend fun advertiseSSDPPacket(data: String, socket: MulticastSocket) {
    val bytes = data.toByteArray(Charsets.UTF_8)
    val packet = DatagramPacket(bytes, bytes.size)
    packet.address = InetAddress.getByName("239.255.255.250")
    packet.port = 1900
    socket.send(packet)
}

suspend fun ackDiscoveryPackets() {
    var gotRequest = false
    var senderPort = -1
    val listenSocket = MulticastSocket(1900)
    listenSocket.joinGroup(InetAddress.getByName("239.255.255.250"))

    GlobalScope.launch { constantlyAdvertiseSSDP(listenSocket) }

    while(!gotRequest) {
        var receivedData = ByteArray(255)
        var receivedPacket = DatagramPacket(receivedData, 255)
        listenSocket.receive(receivedPacket)
        if(receivedPacket.address == InetAddress.getByName(deviceIPAddress)) {
            continue
        }
        val gotText = receivedPacket.data.toString(Charsets.UTF_8)
//        if(gotText.startsWith("M-SEARCH")) {
//            senderPort = receivedPacket.port
////            gotRequest = true;
//            sendUDPPacket("HTTP/1.1 200 OK\r\n" +
//                    "CACHE-CONTROL: max-age=1800\r\n" +
//                    "EXT: \r\n" +
//                    "LOCATION: http://$deviceIPAddress:49152/dms/ddd.xml\r\n" +
//                    "SERVER: Shutterlink/1.0/1\r\n" +
//                    "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
//                    "USN: uuid:$deviceUUID::urn:schemas-upnp-org:device:MediaServer:1\r\n" +
//                    "\r\n", "$cameraIPAddress", senderPort)
//            println("ACKed a request for directory")
//            gotRequest = true
////            picReceiverConnectionStage = 1
//        } else
        if (gotText.startsWith("HTTP/1.1 200 OK")) {
            gotRequest = true;
            println("Got an OK from the camera")
            cameraIPAddress = receivedPacket.address.hostAddress!!
        }
    }
    listenSocket.leaveGroup(InetAddress.getByName("239.255.255.250"))
}

fun getContentLengthFromStream(ins: InputStream): Int {
    var buf: List<Byte> = List<Byte>(0) {'a'.code.toByte()}
    buf += ins.read().toByte()
    buf += ins.read().toByte()
    while(buf[buf.size-2] != '\r'.code.toByte() && buf[buf.size-1] != '\n'.code.toByte()) {
        buf += ins.read().toByte()
    }
    return buf.dropLast(2).toByteArray().decodeToString().toInt(16)
}

suspend fun imageServer(ctx: Context, downloading: (Boolean) -> Unit, downloadProgress: (Float) -> Unit) {
    val ss = ServerSocket(49153)
    while(true) {
        val socket = ss.accept()
        if(socket.inetAddress.hostAddress != cameraIPAddress || !socket.inetAddress.isSiteLocalAddress) {
            socket.close()
            continue
        }
        downloading(true)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        var s = ""
        while (!s.endsWith("\r\n\r\n")) {
            s += input.read().toChar()
        }
        println("Processing incoming image: \n$s")
        out.write(
            ("HTTP/1.1 100 Continue\r\n" +
                    "Content-Length: 0\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.flush()

        val resolver = ctx.contentResolver;
        val info = ContentValues();
        info.put(MediaStore.MediaColumns.DISPLAY_NAME, System.currentTimeMillis().toString());
        info.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        info.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DCIM + File.separator + "Shutterlink Live Transfer"
        );
        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, info);
        val os = resolver.openOutputStream(imageUri!!);
        if (os != null) {
            try {
                var toRead = getContentLengthFromStream(input)
                while(toRead > 0) {
                    var dataChunk = input.readNBytes(toRead)
                    os.write(dataChunk)
                    input.skip(2) //skips the \r\n at the end of the chunk
                    toRead = getContentLengthFromStream(input)
                }
            } catch (e: Exception) {
                println("ERROR: $e")
            }
            os.flush()
            os.close()
        }
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 0\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.flush()
        downloading(false)
    }
}

suspend fun listenForControlRequests(ctx: Context) {
    val ss = ServerSocket(49152)
    while(true) {
        val socket = ss.accept()
        if(cameraIPAddress == "") {
            cameraIPAddress = socket.inetAddress.hostAddress!!
        }
        var request = ""
        var s = "empty"
        val streamReader = BufferedReader(InputStreamReader(socket.getInputStream()))
        while(s != "") {
            s = streamReader.readLine()
            request += s + "\n"
        }
        println("Server got request from ${socket.inetAddress.hostAddress}: $request")
        val writer = PrintWriter(socket.getOutputStream())
        if(request.startsWith("GET /dms/ddd.xml")) {
            val toSend  = "<?xml version=\"1.0\"?>\r\n" +
                    "  <root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:vli=\"urn:schemas-panasonic-com:vli\">\r\n" +
                    "  <specVersion><major>1</major><minor>0</minor></specVersion>\r\n" +
                    "  <device>\r\n" +
                    "    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>\r\n" +
                    "    <friendlyName>Shutterlink</friendlyName>\r\n" +
                    "    <manufacturer>Panasonic</manufacturer>\r\n" +
                    "    <modelName>LUMIX_UP_SERVER_SMP</modelName>\r\n" +
                    "    <modelNumber>1.00</modelNumber>\r\n" +
                    "    <UDN>uuid:$deviceUUID</UDN>\r\n" +
                    "    <dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMS-1.50</dlna:X_DLNADOC>\r\n" +
                    "    <dlna:X_DLNACAP xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">image-upload</dlna:X_DLNACAP>\r\n" +
                    "    <serviceList>\r\n" +
                    "      <service>\r\n" +
                    "        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>\r\n" +
                    "        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>\r\n" +
                    "        <SCPDURL>/dms/sdd_0.xml</SCPDURL>\r\n" +
                    "        <controlURL>/dms/control_0</controlURL>\r\n" +
                    "        <eventSubURL>/dms/event_0</eventSubURL>\r\n" +
                    "      </service>\r\n" +
                    "    </serviceList>\r\n" +
                    "  </device>\r\n" +
                    "</root>"
            writer.write("HTTP/1.1 200 OK\r\n" +
                    "CONTENT-LANGUAGE: ja\r\n" +
                    "CONTENT-LENGTH: ${toSend.toByteArray(Charsets.UTF_8).size}\r\n" +
                    "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                    "SERVER: Shutterlink/1.0/1\r\n" +
                    "CONNECTION: close\r\n" +
                    "\r\n" +
                    toSend
            )
            writer.flush()
//            socket.getOutputStream().flush()
        } else if (request.startsWith("POST /dms/control_0")) {
            var intent = Regex("SOAPACTION: .+\"").find(request)!!.value
            intent = intent.substring(intent.indexOf("#") + 1)

            if(intent == "X_GetDLNAUploadProfiles\"") {
                val toSend =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                            " <s:Body>\r\n" +
                            "  <u:X_GetDLNAUploadProfilesResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">\r\n" +
                            "   <SupportedUploadProfiles>JPEG_LRG</SupportedUploadProfiles>\r\n" +
                            "  </u:X_GetDLNAUploadProfilesResponse>\r\n" +
                            " </s:Body>\r\n" +
                            "</s:Envelope>"

                writer.write(
                    "HTTP/1.1 200 OK\r\n" +
                            "CONTENT-LENGTH: ${toSend.toByteArray(Charsets.UTF_8).size}\r\n" +
                            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                            "EXT:\r\n" +
                            "SERVER: Shutterlink/1.0/1\r\n" +
                            "CONNECTION: close\r\n" +
                            "\r\n" +
                            toSend
                )
                writer.flush()
                picReceiverConnectionStage = 4
            } else if (intent == "CreateObject\"") {
                s = "empty"
                while(s != "</s:Envelope>") { //this part looks better if you don't look at it....
                    s = streamReader.readLine()
                    request += s + "\n"
                }
                var imageName = Regex("&lt;dc:title&gt;\\w+&lt;/dc:title&gt;").find(request)!!.value
                imageName = imageName.substring(16, imageName.length - 17)
                println(imageName)

                val toSend =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                            " <s:Body>\r\n" +
                            "  <u:CreateObjectResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">\r\n" +
                            "   <ObjectID>1-media-i</ObjectID>\r\n" +
                            "   <Result>&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot; xmlns=&quot;urn:schemas-upnporg:metadata-1-0/DIDL-Lite&quot;&gt;&lt;item id=&quot;1-media-i&quot; parentID=&quot;i&quot; restricted=&quot;0&quot;&gt;&lt;dc:title&gt;$imageName&lt;/dc:title&gt;&lt;res importUri=&quot;http://$deviceIPAddress:49153/MEDIA-027-i-1&quot; protocolInfo=&quot;*:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_CI=0&quot;/&gt;&lt;upnp:class&gt;object.item.imageItem&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</Result>\r\n" +
                            "  </u:CreateObjectResponse>\r\n" +
                            " </s:Body>\r\n" +
                            "</s:Envelope>"

                writer.write("HTTP/1.1 200 OK\r\n" +
                        "CONTENT-LENGTH: ${toSend.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                        "EXT:\r\n" +
                        "SERVER: Shutterlink/1.0/1\r\n" +
                        "CONNECTION: close\r\n" +
                        "\r\n" + toSend)
                writer.flush()
            }


        }
        socket.close()
    }
}

fun getIPAddress() {
    var addresses = List<InetAddress>(0) {InetAddress.getByName("127.0.0.1")}
    Collections.list(NetworkInterface.getNetworkInterfaces()).filter { net -> !net.isPointToPoint() }.forEach{it -> addresses += it.inetAddresses.toList().filter {add->add.isSiteLocalAddress} }
    addresses.forEach { it->println(it) }
    if(addresses.size > 1) { //Can happen if the user has just transitioned to the camera wifi, or if they somehow have more than one [non-P2P] network connection (like with an ethernet dongle or something)
        if(addresses.filter {add->add.hostAddress.startsWith("192.168.54.")}.size > 0) { //both cameras I have use this subnet for their hotspot, so I'm hopeful that it's consistent.
            deviceIPAddress = addresses.filter {add->add.hostAddress.startsWith("192.168.54")}[0].hostAddress!!
        } else {
            deviceIPAddress = addresses[0].hostAddress!!
        }
    } else {
        deviceIPAddress = addresses[0].hostAddress!!
    }

}

suspend fun registerAsReceiver(ctx: Context, progressText:(String) -> Unit, downloading: (Boolean) -> Unit, downloadProgress: (Float) -> Unit) {
    progressText("Detecting Device IP Address")
    getIPAddress()
    progressText("Searching for Camera")
    GlobalScope.launch { listenForControlRequests(ctx) }
    GlobalScope.launch{ imageServer(ctx, downloading, downloadProgress)}
    GlobalScope.launch { ackDiscoveryPackets() }
    while(cameraIPAddress == "") {}
    progressText("Attempting to pair, check camera")
    try {
        var url = URL("http://$cameraIPAddress:80/cam.cgi?mode=accctrl&type=req_acc&value=0&value2=Shutterlink")
        var con = url.openConnection() as HttpURLConnection
        delay(125);
        println("Sent Pairing Request to Camera: " + con.responseMessage)
        con.disconnect();
    } catch(e: Exception) {
        println("Failed to send control request: \n$e")
    }
}

fun captureImage() {
    if(cameraMode != "rec") {
        try {
            var url = URL("http://$cameraIPAddress:80/cam.cgi?mode=camcmd&value=recmode")
            var con = url.openConnection() as HttpURLConnection
//        delay(125);
            println("Requesting record mode " + con.responseMessage)
            con.disconnect();
        } catch(e: Exception) {
            println("Failed to send control request: \n$e")
        }
    }
        try {
            var url = URL("http://$cameraIPAddress:80/cam.cgi?mode=camcmd&value=capture")
            var con = url.openConnection() as HttpURLConnection
//        delay(125);
            println("Asked camera to take a picture " + con.responseMessage)
            con.disconnect();
        } catch (e: Exception) {
            println("Failed to send control request: \n$e")
        }
}

suspend fun downloadThumbnail(context: Context, pic: String, downloadLocation: (String) -> Unit) {
    var url = URL("http://$cameraIPAddress:50001/$pic")
    val imageData = url.readBytes();
    val tempFile = File.createTempFile(pic, null, context.cacheDir);
    tempFile.appendBytes(imageData)
    tempFile.renameTo(File(context.cacheDir, pic))
    downloadLocation(pic)
}


const val BATCH_PAUSE_MS = 2000L //let the camera finish processing before the next picture

/** A picture on the camera: its thumbnail file name (DT*) and the date it was taken, if reported. */
data class CameraPicture(val thumbName: String, val date: LocalDate?)

/** Browses [count] items starting at the camera index [startingIndex] (oldest first). */
private fun browsePictures(startingIndex: Int, count: Int): List<CameraPicture> {
    val con = URL("http://$cameraIPAddress:60606/Server0/CDS_control").openConnection() as HttpURLConnection
    con.connectTimeout = CAMERA_TIMEOUT_MS
    con.readTimeout = CAMERA_TIMEOUT_MS
    try {
        con.requestMethod = "POST"
        con.setRequestProperty("Content-Type", "text/xml");
        con.setRequestProperty("SOAPACTION", "urn:schemas-upnp-org:service:ContentDirectory:1#Browse");
        con.doOutput = true;
        con.outputStream.use { os ->
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\" xmlns:pana=\"urn:schemas-panasonic-com:pana\"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$startingIndex</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria><pana:X_FromCP>Shutterlink</pana:X_FromCP></u:Browse></s:Body></s:Envelope>".toByteArray(Charsets.UTF_8)
            os.write(body, 0, body.size)
        }
        // The DIDL-Lite result is XML-escaped inside the SOAP response
        val data = con.inputStream.bufferedReader().readText()
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        val urlRegex = Regex("http://\\d{2,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+/(DT\\w+\\.JPG)")
        val dateRegex = Regex("<dc:date>\\s*(\\d{4}-\\d{2}-\\d{2})")
        return data.split("<item").drop(1).mapNotNull { item ->
            val name = urlRegex.find(item)?.groupValues?.get(1) ?: return@mapNotNull null
            val date = dateRegex.find(item)?.groupValues?.get(1)?.let { LocalDate.parse(it) }
            CameraPicture(name, date)
        } //oldest first, like the camera lists them
    } finally {
        con.disconnect()
    }
}

/**
 * Lists the pictures taken on or after [cutoff], newest first. Pages are read from the newest
 * picture backwards and reading stops at the first picture older than the cutoff.
 */
suspend fun listPicturesSince(cutoff: LocalDate): List<CameraPicture> = withContext(Dispatchers.IO) {
    val result = ArrayList<CameraPicture>()
    var page = 0
    while (true) {
        val first = page * imagesPerRequest
        val count = minOf(imagesPerRequest, numPicsOnCamera - first)
        if (count <= 0) break
        val items = browsePictures(numPicsOnCamera - first - count, count).reversed()
        for (item in items) {
            val date = item.date ?: throw IllegalStateException("The camera did not report picture dates")
            if (date < cutoff) return@withContext result
            result.add(item)
        }
        page++
        delay(THUMBNAIL_PAUSE_MS)
    }
    result
}

/**
 * Downloads the originals of [pictures] one by one, pausing [BATCH_PAUSE_MS] between them so the
 * camera has time to process. [onProgress] receives (finished so far, failed so far).
 * Returns the number of failed pictures.
 */
suspend fun downloadBatch(ctx: Context, pictures: List<CameraPicture>, onProgress: (Int, Int) -> Unit): Int {
    var failed = 0
    onProgress(0, 0)
    for ((i, pic) in pictures.withIndex()) {
        try {
            withContext(Dispatchers.IO) { downloadHQImage(pic.thumbName, ctx, {}, {}) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Batch download of ${pic.thumbName} failed: $e")
            failed++
        }
        onProgress(i + 1, failed)
        if (i < pictures.size - 1) delay(BATCH_PAUSE_MS)
    }
    return failed
}

/** Names (DO*.JPG / DO*.mp4) of files already saved in the camera's download folder (or the old DCIM/Shutterlink). */
private fun alreadyDownloadedNames(ctx: Context): Set<String> {
    val names = HashSet<String>()
    for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("DCIM/${downloadFolder()}/%", "DCIM/Shutterlink/%"), //also the old folder name
            null
        )?.use { c -> while (c.moveToNext()) names.add(c.getString(0)) }
    }
    return names
}

/** Splits [pictures] into those still to download and the number already saved on the phone. */
suspend fun skipAlreadyDownloaded(ctx: Context, pictures: List<CameraPicture>): Pair<List<CameraPicture>, Int> =
    withContext(Dispatchers.IO) {
        val have = alreadyDownloadedNames(ctx)
        val todo = pictures.filter {
            val base = "DO" + it.thumbName.substring(2, it.thumbName.length - 3)
            !(have.contains(base + "JPG") || have.contains(base + "mp4"))
        }
        todo to (pictures.size - todo.size)
    }

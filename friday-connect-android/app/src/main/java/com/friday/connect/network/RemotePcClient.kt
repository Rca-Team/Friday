package com.friday.connect.network

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.friday.connect.core.DeviceCredential
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemotePcClient(private val context: Context, private val credentialProvider: () -> DeviceCredential?) {
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val cred = credentialProvider()
            val request = chain.request().newBuilder()
            if (cred != null) {
                request.header("X-Friday-Device", cred.deviceId)
                request.header("Authorization", "Bearer ${cred.deviceSecret}")
            }
            chain.proceed(request.build())
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun getBaseUrl(): String {
        val cred = credentialProvider() ?: return ""
        return runCatching { com.friday.connect.assistant.GatewaySecurity.base(cred) }.getOrDefault("")
    }

    fun downloadScreenshot(): Long {
        val cred = credentialProvider() ?: return -1L
        val base = getBaseUrl()
        if (base.isBlank()) return -1L
        return try {
            val request = DownloadManager.Request(Uri.parse("$base/api/remote/pc/screenshot"))
                .addRequestHeader("X-Friday-Device", cred.deviceId)
                .addRequestHeader("Authorization", "Bearer ${cred.deviceSecret}")
                .setTitle("Friday PC screenshot")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Friday/screenshot-${System.currentTimeMillis()}.jpg")
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        } catch (_: Exception) { -1L }
    }

    fun executeTerminal(command: String, onResult: (Boolean, String) -> Unit) {
        val base = getBaseUrl()
        if (base.isBlank()) {
            onResult(false, "Gateway URL not configured")
            return
        }
        val body = JSONObject().put("command", command).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$base/api/remote/pc/execute")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "Request failed")
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                try {
                    val json = JSONObject(raw)
                    val out = json.optString("stdout")
                    val err = json.optString("stderr")
                    val text = when {
                        out.isNotBlank() && err.isNotBlank() -> "$out\n[ERR]: $err"
                        out.isNotBlank() -> out
                        err.isNotBlank() -> "[ERR]: $err"
                        else -> "Command completed with exit code ${json.optInt("exit_code")}"
                    }
                    onResult(response.isSuccessful, text)
                } catch (_: Exception) {
                    onResult(response.isSuccessful, raw)
                }
            }
        })
    }

    fun controlMedia(action: String, level: Int? = null, onResult: (Boolean, String) -> Unit) {
        val base = getBaseUrl()
        if (base.isBlank()) {
            onResult(false, "Gateway not connected")
            return
        }
        val json = JSONObject().put("action", action)
        if (level != null) json.put("level", level)
        val body = json.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$base/api/remote/pc/media")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                onResult(response.isSuccessful, if (response.isSuccessful) "Media: $action" else "Failed")
            }
        })
    }

    fun controlApp(action: String, appName: String, onResult: (Boolean, String) -> Unit) {
        val base = getBaseUrl()
        if (base.isBlank()) {
            onResult(false, "Gateway not connected")
            return
        }
        val body = JSONObject().put("action", action).put("app_name", appName).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$base/api/remote/pc/app")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                onResult(response.isSuccessful, raw)
            }
        })
    }

    fun controlPower(action: String, onResult: (Boolean, String) -> Unit) {
        val base = getBaseUrl()
        if (base.isBlank()) {
            onResult(false, "Gateway not connected")
            return
        }
        val body = JSONObject().put("action", action).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$base/api/remote/pc/power")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                onResult(response.isSuccessful, raw)
            }
        })
    }

    fun fetchVitals(onResult: (Map<String, Any?>?) -> Unit) {
        val base = getBaseUrl()
        if (base.isBlank()) {
            onResult(null)
            return
        }
        val request = Request.Builder()
            .url("$base/api/remote/pc/vitals")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                try {
                    val json = JSONObject(raw)
                    if (json.optBoolean("ok")) {
                        val map = mutableMapOf<String, Any?>()
                        json.keys().forEach { key -> map[key] = json.opt(key) }
                        onResult(map)
                    } else {
                        onResult(null)
                    }
                } catch (_: Exception) {
                    onResult(null)
                }
            }
        })
    }

    fun browseFiles(path: String = "", onResult: (Boolean, String, String, List<Map<String, Any?>>) -> Unit) {
        val base = getBaseUrl()
        if (base.isBlank()) {
            onResult(false, "", "", emptyList())
            return
        }
        val encoded = Uri.encode(path)
        val request = Request.Builder()
            .url("$base/api/remote/files/browse?path=$encoded")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, "", "", emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                try {
                    val json = JSONObject(raw)
                    if (json.optBoolean("ok")) {
                        val current = json.optString("current_path")
                        val parent = json.optString("parent")
                        val array = json.optJSONArray("entries") ?: JSONArray()
                        val list = mutableListOf<Map<String, Any?>>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            list.add(mapOf(
                                "name" to obj.optString("name"),
                                "path" to obj.optString("path"),
                                "is_dir" to obj.optBoolean("is_dir"),
                                "size_bytes" to obj.optLong("size_bytes"),
                                "type" to obj.optString("type")
                            ))
                        }
                        onResult(true, current, parent, list)
                    } else {
                        onResult(false, "", "", emptyList())
                    }
                } catch (_: Exception) {
                    onResult(false, "", "", emptyList())
                }
            }
        })
    }

    fun downloadFileOrFolder(pcPath: String, filename: String? = null): Long {
        val base = getBaseUrl()
        if (base.isBlank()) return -1L
        val encoded = Uri.encode(pcPath)
        val url = "$base/api/remote/files/download?path=$encoded"
        val resolvedName = filename ?: pcPath.substringAfterLast('\\').substringAfterLast('/').ifBlank { "friday_download.zip" }.let {
            if (it.contains(".")) it else "$it.zip"
        }

        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cred = credentialProvider() ?: return -1L
            val request = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("X-Friday-Device", cred.deviceId)
                .addRequestHeader("Authorization", "Bearer ${cred.deviceSecret}")
                .setTitle("Friday: $resolvedName")
                .setDescription("Downloading directly from PC")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Friday/$resolvedName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            dm.enqueue(request)
        } catch (_: Exception) {
            -1L
        }
    }

    fun uploadFileToPc(uri: Uri, targetDirectory: String = "", onResult: (Boolean, String) -> Unit) {
        val base = getBaseUrl()
        if (base.isBlank()) {
            onResult(false, "Gateway not connected")
            return
        }
        try {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
            } ?: "mobile-file"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                onResult(false, "Could not read the selected file")
                return
            }
            val contentType = context.contentResolver.getType(uri)?.toMediaType() ?: "application/octet-stream".toMediaType()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, bytes.toRequestBody(contentType))
                .build()
            val target = Uri.encode(targetDirectory)
            val request = Request.Builder().url("$base/api/remote/files/upload?target_dir=$target").post(body).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = onResult(false, e.message ?: "Upload failed")
                override fun onResponse(call: Call, response: Response) {
                    val raw = response.body?.string().orEmpty()
                    val saved = runCatching { JSONObject(raw).optString("saved_path") }.getOrDefault("")
                    onResult(response.isSuccessful, if (response.isSuccessful) "Saved to $saved" else raw)
                }
            })
        } catch (e: Exception) {
            onResult(false, e.message ?: "Upload failed")
        }
    }

    fun sendRemoteCommand(text: String, onResult: (Boolean, String) -> Unit) {
        val base = getBaseUrl()
        if (base.isBlank()) {
            onResult(false, "Gateway not connected")
            return
        }
        val body = JSONObject().put("text", text).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$base/api/remote/command")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                try {
                    val json = JSONObject(raw)
                    val resp = json.optString("response")
                    onResult(response.isSuccessful, if (resp.isNotBlank()) resp else raw)
                } catch (_: Exception) {
                    onResult(response.isSuccessful, raw)
                }
            }
        })
    }
}

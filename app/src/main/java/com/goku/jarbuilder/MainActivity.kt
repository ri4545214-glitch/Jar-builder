package com.goku.jarbuilder

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val repoName = "jar-builder"
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private lateinit var tokenBox: EditText
    private lateinit var pickBtn: Button
    private lateinit var logView: TextView
    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(uri)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        col.addView(TextView(this).apply { text = "Jar Builder"; textSize = 26f })
        col.addView(TextView(this).apply {
            text = "Pick your mod source .zip. It is built on GitHub and the .jar is saved to your Downloads folder."
            setPadding(0, dp(8), 0, dp(16))
        })
        tokenBox = EditText(this).apply {
            hint = "GitHub token (scopes: repo + workflow)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("token", ""))
        }
        col.addView(tokenBox)
        pickBtn = Button(this).apply {
            text = "Choose source .zip and build"
            setOnClickListener { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        }
        col.addView(pickBtn)
        logView = TextView(this).apply { setPadding(0, dp(16), 0, 0); setTextIsSelectable(true) }
        col.addView(logView)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun say(s: String) = runOnUiThread { logView.append(s + "\n") }

    private fun start(uri: Uri) {
        prefs.edit().putString("token", tokenBox.text.toString().trim()).apply()
        pickBtn.isEnabled = false
        logView.text = ""
        Thread {
            try {
                work(uri)
            } catch (e: Exception) {
                say("Error: ${e.message}")
            } finally {
                runOnUiThread { pickBtn.isEnabled = true }
            }
        }.start()
    }

    private fun call(token: String, method: String, url: String, body: String? = null,
                     accept: String = "application/vnd.github+json"): Pair<Int, String> {
        val rb = body?.toRequestBody("application/json".toMediaType())
            ?: if (method == "GET") null else "".toRequestBody()
        val req = Request.Builder()
            .url(if (url.startsWith("http")) url else "https://api.github.com$url")
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, rb)
            .build()
        http.newCall(req).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private fun putFile(token: String, owner: String, path: String, data: ByteArray) {
        val (c, b) = call(token, "GET", "/repos/$owner/$repoName/contents/$path")
        val j = JSONObject()
            .put("message", "Update $path")
            .put("content", Base64.encodeToString(data, Base64.NO_WRAP))
        if (c == 200) j.put("sha", JSONObject(b).optString("sha"))
        val (c2, b2) = call(token, "PUT", "/repos/$owner/$repoName/contents/$path", j.toString())
        if (c2 !in 200..201) throw RuntimeException("Upload of $path failed ($c2): ${b2.take(200)}")
    }

    private fun work(uri: Uri) {
        val token = tokenBox.text.toString().trim()
        if (token.isEmpty()) { say("Paste your GitHub token first."); return }
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw RuntimeException("Could not read the file")
        if (bytes.size > 60 * 1024 * 1024) throw RuntimeException("Zip is too big (max 60 MB)")

        say("Logging in to GitHub...")
        val (mc, mb) = call(token, "GET", "/user")
        if (mc != 200) throw RuntimeException("Token not accepted ($mc). Check the token.")
        val owner = JSONObject(mb).getString("login")
        say("Logged in as $owner")

        say("Preparing private repo '$repoName'...")
        val (rc, rb) = call(token, "POST", "/user/repos",
            JSONObject().put("name", repoName).put("private", true).put("auto_init", true).toString())
        if (rc == 201) Thread.sleep(4000)
        else if (rc != 422) throw RuntimeException("Could not create repo ($rc): ${rb.take(200)}")

        putFile(token, owner, ".github/workflows/build.yml", assets.open("build.yml").use { it.readBytes() })
        say("Uploading your source (${bytes.size / 1024} KB)...")
        putFile(token, owner, "input/source.zip", bytes)

        val start = Instant.now().minusSeconds(10)
        say("Starting the build...")
        var started = false
        repeat(6) {
            if (!started) {
                val (c, _) = call(token, "POST",
                    "/repos/$owner/$repoName/actions/workflows/build.yml/dispatches",
                    JSONObject().put("ref", "main").toString())
                if (c == 204) started = true else Thread.sleep(4000)
            }
        }
        if (!started) throw RuntimeException("Could not start the build. Is the token's 'workflow' scope ticked?")

        say("Building on GitHub (usually 3-6 minutes)...")
        var conclusion: String? = null
        var runUrl = ""
        var lastStatus = ""
        val deadline = System.currentTimeMillis() + 25 * 60_000
        while (conclusion == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(8000)
            val (c, b) = call(token, "GET", "/repos/$owner/$repoName/actions/runs?event=workflow_dispatch&per_page=5")
            if (c != 200) continue
            val runs = JSONObject(b).getJSONArray("workflow_runs")
            for (i in 0 until runs.length()) {
                val r = runs.getJSONObject(i)
                if (Instant.parse(r.getString("created_at")).isBefore(start)) continue
                val st = r.getString("status")
                if (st != lastStatus) { lastStatus = st; say("Status: $st") }
                if (st == "completed") {
                    conclusion = r.optString("conclusion", "unknown")
                    runUrl = r.getString("html_url")
                }
                break
            }
        }
        if (conclusion == null) throw RuntimeException("Timed out waiting for the build.")
        if (conclusion != "success") {
            say("Build failed ($conclusion). Open this page and send me the error text:\n$runUrl")
            return
        }

        say("Build done. Downloading the .jar...")
        val (c, b) = call(token, "GET", "/repos/$owner/$repoName/releases/tags/latest")
        if (c != 200) throw RuntimeException("Release not found ($c)")
        val assetsArr = JSONObject(b).getJSONArray("assets")
        var id = -1L
        var name = ""
        for (i in 0 until assetsArr.length()) {
            val a = assetsArr.getJSONObject(i)
            if (a.getString("name").endsWith(".jar")) { id = a.getLong("id"); name = a.getString("name"); break }
        }
        if (id < 0) throw RuntimeException("No .jar found in the build output")
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repoName/releases/assets/$id")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/octet-stream")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Download failed (${resp.code})")
            saveToDownloads(name, resp.body!!.byteStream())
        }
        say("Saved to Downloads: $name")
    }

    private fun saveToDownloads(name: String, input: InputStream) {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/java-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw RuntimeException("Could not create file in Downloads")
        contentResolver.openOutputStream(out)!!.use { input.copyTo(it) }
    }
}

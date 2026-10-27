package com.jarbuilder

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var tokenIn: EditText
    private lateinit var repoIn: EditText
    private lateinit var fileLabel: TextView
    private lateinit var buildBtn: Button
    private lateinit var logView: TextView
    private var zipUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, pad / 2, 0, 0) }

        root.addView(TextView(this).apply { text = "Jar Builder"; textSize = 24f })
        root.addView(label("GitHub token (classic, scopes: repo + workflow)"))
        tokenIn = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("token", ""))
        }
        root.addView(tokenIn)
        root.addView(label("Repo name (created for you if missing)"))
        repoIn = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("repo", "jar-builder"))
        }
        root.addView(repoIn)

        val pick = Button(this).apply { text = "Choose mod source .zip" }
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(i, 1)
        }
        root.addView(pick)
        fileLabel = TextView(this).apply { text = "No file chosen" }
        root.addView(fileLabel)

        buildBtn = Button(this).apply { text = "Build .jar" }
        buildBtn.setOnClickListener {
            val token = tokenIn.text.toString().trim()
            val repo = repoIn.text.toString().trim()
            val uri = zipUri
            when {
                token.isEmpty() -> toast("Enter your GitHub token")
                repo.isEmpty() -> toast("Enter a repo name")
                uri == null -> toast("Choose a .zip first")
                else -> {
                    prefs.edit().putString("token", token).putString("repo", repo).apply()
                    buildBtn.isEnabled = false
                    logView.text = ""
                    Thread {
                        try { run(token, repo, uri) } catch (e: Exception) { say("ERROR: ${e.message}") }
                        runOnUiThread { buildBtn.isEnabled = true }
                    }.start()
                }
            }
        }
        root.addView(buildBtn)

        logView = TextView(this).apply { textSize = 13f; setPadding(0, pad, 0, 0); setTextIsSelectable(true) }
        root.addView(logView)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data?.data != null) {
            zipUri = data.data
            var name = "file"
            contentResolver.query(zipUri!!, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
            fileLabel.text = name
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun say(t: String) = runOnUiThread { logView.append(t + "\n") }

    private fun run(token: String, repo: String, uri: Uri) {
        val gh = Gh(token)
        val api = "https://api.github.com"

        say("Checking GitHub login...")
        val (uc, ub) = gh.call("GET", "$api/user")
        if (uc != 200) throw RuntimeException("Login failed ($uc). Check your token.")
        val login = JSONObject(ub).getString("login")

        say("Checking repo $login/$repo...")
        var (rc, rb) = gh.call("GET", "$api/repos/$login/$repo")
        if (rc == 404) {
            say("Creating private repo...")
            val body = JSONObject().put("name", repo).put("private", true).put("auto_init", true).toString()
            val r = gh.call("POST", "$api/user/repos", body)
            if (r.first != 201) throw RuntimeException("Could not create repo (${r.first}): ${r.second.take(200)}")
            rc = r.first; rb = r.second
            Thread.sleep(3000)
        } else if (rc != 200) throw RuntimeException("Repo check failed ($rc)")
        val branch = JSONObject(rb).getString("default_branch")

        fun put(path: String, bytes: ByteArray) {
            val url = "$api/repos/$login/$repo/contents/$path"
            val (gc, gb) = gh.call("GET", "$url?ref=$branch")
            val o = JSONObject().put("message", "Jar Builder: update $path")
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("branch", branch)
            if (gc == 200) o.put("sha", JSONObject(gb).getString("sha"))
            val (pc, pb) = gh.call("PUT", url, o.toString())
            if (pc != 200 && pc != 201) throw RuntimeException("Upload of $path failed ($pc): ${pb.take(200)}")
        }

        say("Uploading build script...")
        put(".github/workflows/build.yml", assets.open("build.yml").readBytes())
        put("template/build.gradle", assets.open("template_build.gradle").readBytes())
        put("template/settings.gradle", assets.open("template_settings.gradle").readBytes())
        put("template/gradle.properties", assets.open("template_gradle.properties").readBytes())
        say("Uploading your zip...")
        val zipBytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        put("source.zip", zipBytes)

        fun latestRunId(): Long {
            val (c, b) = gh.call("GET", "$api/repos/$login/$repo/actions/runs?per_page=1")
            if (c != 200) return 0
            val arr = JSONObject(b).getJSONArray("workflow_runs")
            return if (arr.length() > 0) arr.getJSONObject(0).getLong("id") else 0
        }
        val prevId = latestRunId()

        say("Starting the build...")
        var started = false
        for (i in 1..8) {
            val d = gh.call("POST", "$api/repos/$login/$repo/actions/workflows/build.yml/dispatches",
                JSONObject().put("ref", branch).toString())
            if (d.first == 204) { started = true; break }
            Thread.sleep(5000)
        }
        if (!started) throw RuntimeException("Could not start the build. Is the token missing the 'workflow' scope?")

        say("Building (about 3-6 minutes the first time)...")
        var runId = 0L
        var runUrl = ""
        var conclusion: String? = null
        val deadline = System.currentTimeMillis() + 30 * 60 * 1000
        var lastStatus = ""
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(8000)
            val (c, b) = gh.call("GET", "$api/repos/$login/$repo/actions/runs?event=workflow_dispatch&per_page=5")
            if (c != 200) continue
            val arr: JSONArray = JSONObject(b).getJSONArray("workflow_runs")
            var found: JSONObject? = null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.getLong("id") > prevId && (runId == 0L || r.getLong("id") == runId)) { found = r; break }
            }
            if (found == null) continue
            runId = found.getLong("id")
            runUrl = found.optString("html_url")
            val status = found.getString("status")
            if (status != lastStatus) { say("Status: $status"); lastStatus = status }
            if (status == "completed") { conclusion = found.optString("conclusion"); break }
        }
        if (conclusion == null) throw RuntimeException("Timed out waiting for the build. Check: $runUrl")
        if (conclusion != "success") throw RuntimeException("Build failed. Open this link and send me the error: $runUrl")

        say("Build done. Downloading .jar...")
        val (ac, ab) = gh.call("GET", "$api/repos/$login/$repo/releases/tags/latest")
        if (ac != 200) throw RuntimeException("Could not find the release ($ac)")
        val assets = JSONObject(ab).getJSONArray("assets")
        var best: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.getString("name")
            if (!n.endsWith(".jar") || n.endsWith("-sources.jar")) continue
            if (best == null || a.getString("updated_at") > best.getString("updated_at")) best = a
        }
        if (best == null) throw RuntimeException("No .jar found in the release")
        val name = best.getString("name")
        val bytes = gh.download(best.getString("url"))

        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/java-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw RuntimeException("Could not save the file")
        contentResolver.openOutputStream(out)!!.use { it.write(bytes) }
        say("DONE! Saved $name to your Downloads folder.")
    }
}
